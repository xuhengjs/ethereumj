package org.ethereum.net.eth.handler;

import io.netty.channel.ChannelHandlerContext;
import org.ethereum.core.Block;
import org.ethereum.core.BlockHeader;
import org.ethereum.core.BlockIdentifier;
import org.ethereum.net.eth.EthVersion;
import org.ethereum.net.eth.message.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.*;

import static org.ethereum.net.eth.EthVersion.V62;
import static org.ethereum.sync.SyncStateName.*;
import static org.ethereum.sync.SyncStateName.BLOCK_RETRIEVING;

/**
 * Eth 62
 *
 * @author Mikhail Kalinin
 * @since 04.09.2015
 */
@Component
@Scope("prototype")
public class Eth62 extends EthHandler {

    private final static Logger logger = LoggerFactory.getLogger("sync");

    /**
     * Header list sent in GET_BLOC_BODIES message,
     * useful if returned BLOCKS msg doesn't cover all sent hashes
     * or in case when peer is disconnected
     */
    protected final List<BlockHeader> sentHeaders = Collections.synchronizedList(new ArrayList<BlockHeader>());

    public Eth62() {
        super(V62);
    }

    @Override
    public void channelRead0(final ChannelHandlerContext ctx, EthMessage msg) throws InterruptedException {

        super.channelRead0(ctx, msg);

        switch (msg.getCommand()) {
            case NEW_BLOCK_HASHES:
                processNewBlockHashes((NewBlockHashes62Message) msg);
                break;
            case GET_BLOCK_HEADERS:
                processGetBlockHeaders((GetBlockHeadersMessage) msg);
                break;
            case BLOCK_HEADERS:
                processBlockHeaders((BlockHeadersMessage) msg);
                break;
            case GET_BLOCK_BODIES:
                processGetBlockBodies((GetBlockBodiesMessage) msg);
                break;
            case BLOCK_BODIES:
                processBlockBodies((BlockBodiesMessage) msg);
                break;
            default:
                break;
        }
    }

    @Override
    public void onShutdown() {
        super.onShutdown();
        returnHeaders();
    }

    @Override
    protected void startHashRetrieving() {
        long bestNumber = blockchain.getBestBlock().getNumber();
        sendGetBlockHeaders(bestNumber + 1, maxHashesAsk);
    }

    @Override
    protected boolean startBlockRetrieving() {
        return sendGetBlockBodies();
    }

    private void processNewBlockHashes(NewBlockHashes62Message msg) {

        if(logger.isTraceEnabled()) logger.trace(
                "Peer {}: processing NewBlockHashes, size [{}]",
                channel.getPeerIdShort(),
                msg.getBlockIdentifiers().size()
        );

        List<BlockIdentifier> identifiers = msg.getBlockIdentifiers();

        for (BlockIdentifier identifier : identifiers) {
            if (!queue.isBlockExist(identifier.getHash())
                && !blockchain.isBlockExist(identifier.getHash())) {

                long lastBlockNumber = identifiers.get(identifiers.size() - 1).getNumber();
                int maxBlocksAsk = (int) (lastBlockNumber - identifier.getNumber() + 1);
                sendGetBlockHeaders(identifier.getNumber(), maxBlocksAsk);
                return;
            }
        }
    }

    private void processGetBlockHeaders(GetBlockHeadersMessage msg) {
        List<BlockHeader> headers = blockchain.getListOfHeadersStartFrom(
                msg.getBlockIdentifier(),
                msg.getSkipBlocks(),
                msg.getMaxHeaders(),
                msg.isReverse()
        );

        BlockHeadersMessage response = new BlockHeadersMessage(headers);
        sendMessage(response);
    }

    private void processBlockHeaders(BlockHeadersMessage msg) {

        if(logger.isTraceEnabled()) logger.trace(
                "Peer {}: processing BlockHeaders, size [{}]",
                channel.getPeerIdShort(),
                msg.getBlockHeaders().size()
        );

        if (syncState != HASH_RETRIEVING) {
            return;
        }

        List<BlockHeader> received = msg.getBlockHeaders();
        syncStats.addHashes(received.size());

        if (received.isEmpty()) {
            return;
        }

        queue.addAndValidateHeaders(received, channel.getNodeId());

        for(BlockHeader header : received)
            if (Arrays.equals(header.getHash(), lastHashToAsk)) {
                changeState(DONE_HASH_RETRIEVING);
                logger.trace("Peer {}: got terminal hash [{}]", channel.getPeerIdShort(), Hex.toHexString(lastHashToAsk));
            }

        long lastNumber = received.get(received.size() - 1).getNumber();

        sendGetBlockHeaders(lastNumber + 1, maxHashesAsk);

        if (logger.isInfoEnabled()) {
            if (syncState == DONE_HASH_RETRIEVING) {
                logger.info(
                        "Peer {}: header sync completed, [{}] headers in queue",
                        channel.getPeerIdShort(),
                        queue.headerStoreSize()
                );
            } else {
                queue.logHeadersSize();
            }
        }
    }

    private void processGetBlockBodies(GetBlockBodiesMessage msg) {
        List<byte[]> bodies = blockchain.getListOfBodiesByHashes(msg.getBlockHashes());

        BlockBodiesMessage response = new BlockBodiesMessage(bodies);
        sendMessage(response);
    }

    private void processBlockBodies(BlockBodiesMessage msg) {

        if(logger.isTraceEnabled()) logger.trace(
                "Peer {}: process BlockBodies, size [{}]",
                channel.getPeerIdShort(),
                msg.getBlockBodies().size()
        );

        List<byte[]> bodyList = msg.getBlockBodies();

        syncStats.addBlocks(bodyList.size());

        // create blocks and add them to the queue
        Iterator<byte[]> bodies = bodyList.iterator();
        Iterator<BlockHeader> headers = sentHeaders.iterator();

        List<Block> blocks = new ArrayList<>(bodyList.size());
        List<BlockHeader> coveredHeaders = new ArrayList<>(sentHeaders.size());

        while (bodies.hasNext() && headers.hasNext()) {
            BlockHeader header = headers.next();
            byte[] body = bodies.next();

            Block b = new Block.Builder()
                    .withHeader(header)
                    .withBody(body)
                    .create();

            if (b == null) {
                break;
            }

            coveredHeaders.add(header);
            blocks.add(b);
        }

        // return headers not covered by response
        sentHeaders.removeAll(coveredHeaders);
        returnHeaders();

        if(!blocks.isEmpty()) {
            queue.addList(blocks, channel.getNodeId());
            queue.logHeadersSize();
        } else {
            changeState(BLOCKS_LACK);
        }

        if (syncState == BLOCK_RETRIEVING) {
            sendGetBlockBodies();
        }
    }

    private void sendGetBlockHeaders(long blockNumber, int maxBlocksAsk) {

        if(logger.isTraceEnabled()) logger.trace(
                "Peer {}: send GetBlockHeaders, blockNumber [{}], maxHashesAsk [{}]",
                channel.getPeerIdShort(),
                blockNumber,
                maxHashesAsk
        );

        GetBlockHeadersMessage msg = new GetBlockHeadersMessage(blockNumber, maxBlocksAsk);

        sendMessage(msg);
    }

    private boolean sendGetBlockBodies() {

        List<BlockHeader> headers = queue.pollHeaders();
        if (headers.isEmpty()) {
            if(logger.isInfoEnabled()) logger.trace(
                    "Peer {}: no more headers in queue, idle",
                    channel.getPeerIdShort()
            );
            changeState(IDLE);
            return false;
        }

        sentHeaders.clear();
        sentHeaders.addAll(headers);

        if(logger.isTraceEnabled()) logger.trace(
                "Peer {}: send GetBlockBodies, hashes.count [{}]",
                channel.getPeerIdShort(),
                sentHeaders.size()
        );

        List<byte[]> hashes = new ArrayList<>(headers.size());
        for (BlockHeader header : headers) {
            hashes.add(header.getHash());
        }

        GetBlockBodiesMessage msg = new GetBlockBodiesMessage(hashes);

        sendMessage(msg);

        return true;
    }

    private void returnHeaders() {
        if(logger.isDebugEnabled()) logger.debug(
                "Peer {}: return [{}] headers back to store",
                channel.getPeerIdShort(),
                sentHeaders.size()
        );

        synchronized (sentHeaders) {
            queue.returnHeaders(sentHeaders);
        }

        sentHeaders.clear();
    }
}
