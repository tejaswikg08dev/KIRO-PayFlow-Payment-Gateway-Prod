package com.payflow.routing.netty;

import com.payflow.routing.iso8583.BitmapUtils;
import com.payflow.routing.iso8583.Iso8583Constants;
import com.payflow.routing.iso8583.Iso8583Field;
import com.payflow.routing.iso8583.Iso8583Message;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Netty encoder that serializes Iso8583Message objects to binary bytes.
 * <p>
 * Wire format (big-endian):
 * [MTI - 4 bytes ASCII] [Bitmap - 8 bytes binary] [Data Fields...]
 */
public class Iso8583Encoder extends MessageToByteEncoder<Iso8583Message> {

    private static final Logger log = LoggerFactory.getLogger(Iso8583Encoder.class);

    private static final Map<Integer, Iso8583Field> FIELD_DEFINITIONS = Iso8583Constants.getFieldDefinitions();

    @Override
    protected void encode(ChannelHandlerContext ctx, Iso8583Message msg, ByteBuf out) throws Exception {
        log.debug("Encoding ISO 8583 message: MTI={}", msg.getMti());

        // Write MTI (4 bytes ASCII)
        out.writeBytes(msg.getMti().getBytes(StandardCharsets.US_ASCII));

        // Write Bitmap (8 bytes binary)
        out.writeBytes(msg.getBitmap());

        // Write data fields in order
        for (int fieldNum = 2; fieldNum <= 64; fieldNum++) {
            if (BitmapUtils.isFieldPresent(msg.getBitmap(), fieldNum)) {
                String value = msg.getField(fieldNum);
                if (value != null) {
                    encodeField(out, fieldNum, value);
                }
            }
        }

        log.debug("Encoded message size: {} bytes", out.readableBytes());
    }

    /**
     * Encodes a single field to the output buffer.
     */
    private void encodeField(ByteBuf out, int fieldNumber, String value) {
        Iso8583Field fieldDef = FIELD_DEFINITIONS.get(fieldNumber);
        if (fieldDef == null) {
            // Unknown field, encode as LLVAR
            String lengthPrefix = String.format("%02d", value.length());
            out.writeBytes(lengthPrefix.getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
            return;
        }

        switch (fieldDef.type()) {
            case NUMERIC -> {
                // Right-justify with leading zeros
                String padded = padLeft(value, fieldDef.maxLength(), '0');
                out.writeBytes(padded.getBytes(StandardCharsets.US_ASCII));
            }
            case ALPHA -> {
                // Left-justify with trailing spaces
                String padded = padRight(value, fieldDef.maxLength(), ' ');
                out.writeBytes(padded.getBytes(StandardCharsets.US_ASCII));
            }
            case LLVAR -> {
                String lengthPrefix = String.format("%02d", value.length());
                out.writeBytes(lengthPrefix.getBytes(StandardCharsets.US_ASCII));
                out.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
            }
            case LLLVAR -> {
                String lengthPrefix = String.format("%03d", value.length());
                out.writeBytes(lengthPrefix.getBytes(StandardCharsets.US_ASCII));
                out.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
            }
        }
    }

    private String padLeft(String value, int length, char padChar) {
        if (value.length() >= length) return value.substring(0, length);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length - value.length(); i++) {
            sb.append(padChar);
        }
        sb.append(value);
        return sb.toString();
    }

    private String padRight(String value, int length, char padChar) {
        if (value.length() >= length) return value.substring(0, length);
        StringBuilder sb = new StringBuilder(length);
        sb.append(value);
        for (int i = 0; i < length - value.length(); i++) {
            sb.append(padChar);
        }
        return sb.toString();
    }
}
