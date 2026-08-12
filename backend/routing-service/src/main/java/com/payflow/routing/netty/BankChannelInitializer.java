package com.payflow.routing.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Initializes the Netty channel pipeline for bank communication.
 * <p>
 * Pipeline:
 * 1. LengthFieldBasedFrameDecoder - Frames incoming messages by 4-byte length prefix
 * 2. LengthFieldPrepender - Prepends 4-byte length to outgoing messages
 * 3. ReadTimeoutHandler - Times out if no response within configured timeout
 * 4. Iso8583Decoder - Decodes binary frames to Iso8583Message
 * 5. Iso8583Encoder - Encodes Iso8583Message to binary frames
 * 6. BankResponseHandler - Completes the CompletableFuture with response
 */
public class BankChannelInitializer extends ChannelInitializer<SocketChannel> {

    private static final Logger log = LoggerFactory.getLogger(BankChannelInitializer.class);

    private static final int MAX_FRAME_LENGTH = 8192;
    private static final int LENGTH_FIELD_OFFSET = 0;
    private static final int LENGTH_FIELD_LENGTH = 4;
    private static final int LENGTH_ADJUSTMENT = 0;
    private static final int INITIAL_BYTES_TO_STRIP = 4;

    private final int readTimeoutSeconds;
    private final BankResponseHandler responseHandler;

    public BankChannelInitializer(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
        this.responseHandler = new BankResponseHandler();
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        log.debug("Initializing bank channel pipeline");

        ChannelPipeline pipeline = ch.pipeline();

        // Frame decoders/encoders (4-byte length prefix, big-endian)
        pipeline.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(
                MAX_FRAME_LENGTH,
                LENGTH_FIELD_OFFSET,
                LENGTH_FIELD_LENGTH,
                LENGTH_ADJUSTMENT,
                INITIAL_BYTES_TO_STRIP
        ));
        pipeline.addLast("framePrepender", new LengthFieldPrepender(LENGTH_FIELD_LENGTH));

        // Read timeout
        pipeline.addLast("readTimeout", new ReadTimeoutHandler(readTimeoutSeconds, TimeUnit.SECONDS));

        // ISO 8583 codec
        pipeline.addLast("iso8583Decoder", new Iso8583Decoder());
        pipeline.addLast("iso8583Encoder", new Iso8583Encoder());

        // Response handler
        pipeline.addLast("responseHandler", responseHandler);
    }

    public BankResponseHandler getResponseHandler() {
        return responseHandler;
    }
}
