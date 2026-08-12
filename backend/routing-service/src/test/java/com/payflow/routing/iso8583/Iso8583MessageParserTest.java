package com.payflow.routing.iso8583;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Iso8583MessageParser Unit Tests")
class Iso8583MessageParserTest {

    private Iso8583MessageParser parser;
    private Iso8583MessageBuilder builder;

    @BeforeEach
    void setUp() {
        parser = new Iso8583MessageParser();
        builder = new Iso8583MessageBuilder();
    }

    @Test
    @DisplayName("parse - should roundtrip build and parse an auth request message")
    void parse_Roundtrip_AuthRequest() {
        // Build a message
        Iso8583Message original = builder
                .setMti(Iso8583Constants.MTI_AUTH_REQUEST)
                .setProcessingCode(Iso8583Constants.PROC_CODE_PURCHASE)
                .setAmount("000000010000")
                .setTraceNumber("654321")
                .setTime("153042")
                .setTerminalId("TERM0001")
                .setCurrencyCode("356")
                .build();

        // Encode the message to bytes
        byte[] encoded = encodeMessage(original);

        // Parse the bytes back
        Iso8583Message parsed = parser.parse(encoded);

        // Verify roundtrip integrity
        assertThat(parsed.getMti()).isEqualTo("0100");
        assertThat(parsed.getField(Iso8583Constants.FIELD_PROCESSING_CODE)).isEqualTo("000000");
        assertThat(parsed.getField(Iso8583Constants.FIELD_AMOUNT)).isEqualTo("000000010000");
        assertThat(parsed.getField(Iso8583Constants.FIELD_TRACE)).isEqualTo("654321");
        assertThat(parsed.getField(Iso8583Constants.FIELD_TIME)).isEqualTo("153042");
        assertThat(parsed.getField(Iso8583Constants.FIELD_TERMINAL_ID)).isEqualTo("TERM0001");
        assertThat(parsed.getField(Iso8583Constants.FIELD_CURRENCY_CODE)).isEqualTo("356");
    }

    @Test
    @DisplayName("parse - should correctly parse MTI from byte array")
    void parse_CorrectMti() {
        Iso8583Message original = builder
                .setMti(Iso8583Constants.MTI_FINANCIAL_REQUEST)
                .setAmount("000000099900")
                .setTraceNumber("000001")
                .build();

        byte[] encoded = encodeMessage(original);
        Iso8583Message parsed = parser.parse(encoded);

        assertThat(parsed.getMti()).isEqualTo("0200");
    }

    @Test
    @DisplayName("parse - should throw Iso8583ParseException for insufficient data")
    void parse_InsufficientData_ThrowsException() {
        byte[] shortData = new byte[5]; // Less than minimum (12 bytes)

        assertThatThrownBy(() -> parser.parse(shortData))
                .isInstanceOf(Iso8583MessageParser.Iso8583ParseException.class)
                .hasMessageContaining("insufficient data");
    }

    @Test
    @DisplayName("parse - should throw Iso8583ParseException for null data")
    void parse_NullData_ThrowsException() {
        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOf(Iso8583MessageParser.Iso8583ParseException.class);
    }

    @Test
    @DisplayName("parse - roundtrip should preserve all field values with LLVAR fields")
    void parse_Roundtrip_WithLlvarFields() {
        Iso8583Message original = builder
                .setMti("0100")
                .setPan("4111111111111111")
                .setAmount("000000050000")
                .setTraceNumber("999999")
                .build();

        byte[] encoded = encodeMessage(original);
        Iso8583Message parsed = parser.parse(encoded);

        assertThat(parsed.getMti()).isEqualTo("0100");
        assertThat(parsed.getField(Iso8583Constants.FIELD_PAN)).isEqualTo("4111111111111111");
        assertThat(parsed.getField(Iso8583Constants.FIELD_AMOUNT)).isEqualTo("000000050000");
        assertThat(parsed.getField(Iso8583Constants.FIELD_TRACE)).isEqualTo("999999");
    }

    /**
     * Helper method to encode an Iso8583Message to bytes using the field definitions.
     * Format: [MTI 4 bytes] [Bitmap 8 bytes] [Data fields...]
     */
    private byte[] encodeMessage(Iso8583Message message) {
        Map<Integer, Iso8583Field> fieldDefs = Iso8583Constants.getFieldDefinitions();
        ByteBuffer buffer = ByteBuffer.allocate(1024);

        // Write MTI (4 bytes ASCII)
        buffer.put(message.getMti().getBytes(StandardCharsets.US_ASCII));

        // Write Bitmap (8 bytes)
        buffer.put(message.getBitmap());

        // Write data fields in order
        for (int fieldNum = 2; fieldNum <= 64; fieldNum++) {
            if (message.hasField(fieldNum)) {
                Iso8583Field fieldDef = fieldDefs.get(fieldNum);
                if (fieldDef == null) continue;

                String value = message.getField(fieldNum);
                switch (fieldDef.type()) {
                    case NUMERIC, ALPHA -> {
                        // Fixed-length: pad to maxLength
                        String padded = String.format("%-" + fieldDef.maxLength() + "s", value);
                        buffer.put(padded.getBytes(StandardCharsets.US_ASCII));
                    }
                    case LLVAR -> {
                        // 2-digit length prefix + value
                        String lengthPrefix = String.format("%02d", value.length());
                        buffer.put(lengthPrefix.getBytes(StandardCharsets.US_ASCII));
                        buffer.put(value.getBytes(StandardCharsets.US_ASCII));
                    }
                    case LLLVAR -> {
                        // 3-digit length prefix + value
                        String lengthPrefix3 = String.format("%03d", value.length());
                        buffer.put(lengthPrefix3.getBytes(StandardCharsets.US_ASCII));
                        buffer.put(value.getBytes(StandardCharsets.US_ASCII));
                    }
                }
            }
        }

        // Return only the written portion
        byte[] result = new byte[buffer.position()];
        buffer.flip();
        buffer.get(result);
        return result;
    }
}
