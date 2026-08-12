package com.payflow.routing.iso8583;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses binary ISO 8583 messages into Iso8583Message objects.
 * <p>
 * Binary format:
 * [MTI - 4 bytes ASCII] [Bitmap - 8 bytes] [Data Fields...]
 */
public class Iso8583MessageParser {

    private static final Logger log = LoggerFactory.getLogger(Iso8583MessageParser.class);

    private static final Map<Integer, Iso8583Field> FIELD_DEFINITIONS = Iso8583Constants.getFieldDefinitions();

    /**
     * Parses a binary byte array into an Iso8583Message.
     *
     * @param data Raw binary ISO 8583 message bytes
     * @return Parsed Iso8583Message
     * @throws Iso8583ParseException if the message cannot be parsed
     */
    public Iso8583Message parse(byte[] data) {
        if (data == null || data.length < 12) {
            throw new Iso8583ParseException("Invalid ISO 8583 message: insufficient data length");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);

        // Parse MTI (4 bytes ASCII)
        byte[] mtiBytes = new byte[4];
        buffer.get(mtiBytes);
        String mti = new String(mtiBytes, StandardCharsets.US_ASCII);

        // Parse Bitmap (8 bytes - 64 bits primary bitmap)
        byte[] bitmap = new byte[8];
        buffer.get(bitmap);

        // Parse data fields based on bitmap
        Map<Integer, String> fields = new HashMap<>();
        for (int fieldNum = 2; fieldNum <= 64; fieldNum++) {
            if (BitmapUtils.isFieldPresent(bitmap, fieldNum)) {
                String value = parseField(buffer, fieldNum);
                if (value != null) {
                    fields.put(fieldNum, value);
                }
            }
        }

        log.debug("Parsed ISO 8583 message: MTI={}, fields={}", mti, fields.keySet());
        return new Iso8583Message(mti, fields, bitmap);
    }

    /**
     * Parses a single field from the buffer based on field definitions.
     */
    private String parseField(ByteBuffer buffer, int fieldNumber) {
        Iso8583Field fieldDef = FIELD_DEFINITIONS.get(fieldNumber);
        if (fieldDef == null) {
            log.warn("No field definition for field {}, skipping", fieldNumber);
            return null;
        }

        try {
            return switch (fieldDef.type()) {
                case NUMERIC -> parseFixedField(buffer, fieldDef.maxLength());
                case ALPHA -> parseFixedField(buffer, fieldDef.maxLength());
                case LLVAR -> parseVariableField(buffer, 2);
                case LLLVAR -> parseVariableField(buffer, 3);
            };
        } catch (Exception e) {
            log.error("Error parsing field {}: {}", fieldNumber, e.getMessage());
            throw new Iso8583ParseException("Failed to parse field " + fieldNumber, e);
        }
    }

    /**
     * Parses a fixed-length field.
     */
    private String parseFixedField(ByteBuffer buffer, int length) {
        if (buffer.remaining() < length) {
            throw new Iso8583ParseException("Insufficient data for fixed field");
        }
        byte[] fieldBytes = new byte[length];
        buffer.get(fieldBytes);
        return new String(fieldBytes, StandardCharsets.US_ASCII).trim();
    }

    /**
     * Parses a variable-length field with LL or LLL prefix.
     */
    private String parseVariableField(ByteBuffer buffer, int lengthDigits) {
        if (buffer.remaining() < lengthDigits) {
            throw new Iso8583ParseException("Insufficient data for variable field length prefix");
        }
        byte[] lengthBytes = new byte[lengthDigits];
        buffer.get(lengthBytes);
        int length = Integer.parseInt(new String(lengthBytes, StandardCharsets.US_ASCII));

        if (buffer.remaining() < length) {
            throw new Iso8583ParseException("Insufficient data for variable field value");
        }
        byte[] fieldBytes = new byte[length];
        buffer.get(fieldBytes);
        return new String(fieldBytes, StandardCharsets.US_ASCII);
    }

    /**
     * Exception thrown when an ISO 8583 message cannot be parsed.
     */
    public static class Iso8583ParseException extends RuntimeException {
        public Iso8583ParseException(String message) {
            super(message);
        }

        public Iso8583ParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
