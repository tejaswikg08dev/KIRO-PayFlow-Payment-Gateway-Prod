package com.payflow.routing.iso8583;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Iso8583MessageBuilder Unit Tests")
class Iso8583MessageBuilderTest {

    private Iso8583MessageBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new Iso8583MessageBuilder();
    }

    @Test
    @DisplayName("build - should create auth request message with correct MTI")
    void build_AuthRequestMessage_HasCorrectMti() {
        Iso8583Message message = builder
                .setMti(Iso8583Constants.MTI_AUTH_REQUEST)
                .setPan("4111111111111111")
                .setProcessingCode(Iso8583Constants.PROC_CODE_PURCHASE)
                .setAmount("000000010000")
                .setTraceNumber("123456")
                .setTime("143025")
                .setTerminalId("TERM0001")
                .setCurrencyCode("356")
                .build();

        assertThat(message).isNotNull();
        assertThat(message.getMti()).isEqualTo("0100");
    }

    @Test
    @DisplayName("build - should set PAN in field 2")
    void build_SetsFieldValues() {
        Iso8583Message message = builder
                .setMti("0100")
                .setPan("4111111111111111")
                .setAmount("000000010000")
                .setTraceNumber("123456")
                .build();

        assertThat(message.getField(Iso8583Constants.FIELD_PAN)).isEqualTo("4111111111111111");
        assertThat(message.getField(Iso8583Constants.FIELD_AMOUNT)).isEqualTo("000000010000");
        assertThat(message.getField(Iso8583Constants.FIELD_TRACE)).isEqualTo("123456");
    }

    @Test
    @DisplayName("build - should set bitmap correctly for present fields")
    void build_SetsBitmapForPresentFields() {
        Iso8583Message message = builder
                .setMti("0200")
                .setPan("5500000000000004")
                .setAmount("000000025000")
                .setTerminalId("TERM0002")
                .setCurrencyCode("840")
                .build();

        byte[] bitmap = message.getBitmap();
        assertThat(bitmap).isNotNull();
        assertThat(bitmap).hasSize(8);

        // Verify fields are present in bitmap
        assertThat(BitmapUtils.isFieldPresent(bitmap, Iso8583Constants.FIELD_PAN)).isTrue();
        assertThat(BitmapUtils.isFieldPresent(bitmap, Iso8583Constants.FIELD_AMOUNT)).isTrue();
        assertThat(BitmapUtils.isFieldPresent(bitmap, Iso8583Constants.FIELD_TERMINAL_ID)).isTrue();
        assertThat(BitmapUtils.isFieldPresent(bitmap, Iso8583Constants.FIELD_CURRENCY_CODE)).isTrue();

        // Verify fields that are NOT present
        assertThat(BitmapUtils.isFieldPresent(bitmap, Iso8583Constants.FIELD_TRACE)).isFalse();
    }

    @Test
    @DisplayName("build - should throw IllegalStateException when MTI is not set")
    void build_NoMti_ThrowsException() {
        builder.setPan("4111111111111111")
                .setAmount("000000010000");

        assertThatThrownBy(() -> builder.build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MTI must be set");
    }

    @Test
    @DisplayName("setMti - should throw IllegalArgumentException for invalid MTI")
    void setMti_InvalidMti_ThrowsException() {
        assertThatThrownBy(() -> builder.setMti("01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MTI must be a 4-digit string");
    }

    @Test
    @DisplayName("setField - should throw IllegalArgumentException for invalid field number")
    void setField_InvalidFieldNumber_ThrowsException() {
        assertThatThrownBy(() -> builder.setField(0, "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Field number must be between 2 and 128");

        assertThatThrownBy(() -> builder.setField(129, "value"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
