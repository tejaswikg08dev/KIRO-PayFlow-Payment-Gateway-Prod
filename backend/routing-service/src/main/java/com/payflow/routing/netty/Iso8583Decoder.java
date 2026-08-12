package com.payflow.routing.netty;

import com.payflow.routing.iso8583.Iso8583Message;
import com.payflow.routing.iso8583.Iso8583MessageParser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Netty decoder that converts incoming binary bytes to Iso8583Message objects.
 * <p>
 * Expects the frame to already be delimited by LengthFieldBasedFrameDecoder
 * in the pipeline.
 */
public class Iso8583Decoder extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(Iso8583Decoder.class);

    private final Iso8583MessageParser parser;

    public Iso8583Decoder() {
        this.parser = new Iso8583MessageParser();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < 12) {
            // Minimum: 4 (MTI) + 8 (bitmap) = 12 bytes
            return;
        }

        byte[] data = new byte[in.readableBytes()];
        in.readBytes(data);

        try {
            Iso8583Message message = parser.parse(data);
            out.add(message);
            log.debug("Decoded ISO 8583 message: MTI={}, fields={}",
                    message.getMti(), message.getFields().keySet());
        } catch (Iso8583MessageParser.Iso8583ParseException e) {
            log.error("Failed to decode ISO 8583 message: {}", e.getMessage());
            ctx.fireExceptionCaught(e);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error in ISO 8583 decoder: {}", cause.getMessage(), cause);
        ctx.close();
    }
}
