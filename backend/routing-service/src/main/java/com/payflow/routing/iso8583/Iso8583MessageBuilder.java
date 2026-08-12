package com.payflow.routing.iso8583;

import java.util.HashMap;
import java.util.Map;

/**
 * Fluent builder for constructing ISO 8583 messages.
 * <p>
 * Usage:
 * <pre>
 * Iso8583Message message = new Iso8583MessageBuilder()
 *     .setMti("0100")
 *     .setField(Iso8583Constants.FIELD_PAN, "4111111111111111")
 *     .setField(Iso8583Constants.FIELD_AMOUNT, "000000010000")
 *     .build();
 * </pre>
 */
public class Iso8583MessageBuilder {

    private String mti;
    private final Map<Integer, String> fields;
    private final byte[] bitmap;

    public Iso8583MessageBuilder() {
        this.fields = new HashMap<>();
        this.bitmap = new byte[8]; // 64-bit primary bitmap
    }

    /**
     * Sets the Message Type Indicator (MTI).
     *
     * @param mti 4-digit MTI code (e.g., "0100", "0200")
     * @return this builder for chaining
     */
    public Iso8583MessageBuilder setMti(String mti) {
        if (mti == null || mti.length() != 4) {
            throw new IllegalArgumentException("MTI must be a 4-digit string");
        }
        this.mti = mti;
        return this;
    }

    /**
     * Sets a field value by field number.
     *
     * @param fieldNumber ISO 8583 field number (2-128)
     * @param value       Field value
     * @return this builder for chaining
     */
    public Iso8583MessageBuilder setField(int fieldNumber, String value) {
        if (fieldNumber < 2 || fieldNumber > 128) {
            throw new IllegalArgumentException("Field number must be between 2 and 128");
        }
        if (value == null) {
            throw new IllegalArgumentException("Field value cannot be null");
        }
        fields.put(fieldNumber, value);
        BitmapUtils.setFieldPresent(bitmap, fieldNumber);
        return this;
    }

    /**
     * Sets the Primary Account Number (PAN) - Field 2.
     */
    public Iso8583MessageBuilder setPan(String pan) {
        return setField(Iso8583Constants.FIELD_PAN, pan);
    }

    /**
     * Sets the Processing Code - Field 3.
     */
    public Iso8583MessageBuilder setProcessingCode(String code) {
        return setField(Iso8583Constants.FIELD_PROCESSING_CODE, code);
    }

    /**
     * Sets the Transaction Amount - Field 4.
     */
    public Iso8583MessageBuilder setAmount(String amount) {
        return setField(Iso8583Constants.FIELD_AMOUNT, amount);
    }

    /**
     * Sets the System Trace Audit Number - Field 11.
     */
    public Iso8583MessageBuilder setTraceNumber(String trace) {
        return setField(Iso8583Constants.FIELD_TRACE, trace);
    }

    /**
     * Sets the Transaction Time - Field 12.
     */
    public Iso8583MessageBuilder setTime(String time) {
        return setField(Iso8583Constants.FIELD_TIME, time);
    }

    /**
     * Sets the Terminal ID - Field 41.
     */
    public Iso8583MessageBuilder setTerminalId(String terminalId) {
        return setField(Iso8583Constants.FIELD_TERMINAL_ID, terminalId);
    }

    /**
     * Sets the Merchant Name - Field 43.
     */
    public Iso8583MessageBuilder setMerchantName(String name) {
        return setField(Iso8583Constants.FIELD_MERCHANT_NAME, name);
    }

    /**
     * Sets the Currency Code - Field 49.
     */
    public Iso8583MessageBuilder setCurrencyCode(String currencyCode) {
        return setField(Iso8583Constants.FIELD_CURRENCY_CODE, currencyCode);
    }

    /**
     * Builds the ISO 8583 message from the configured fields.
     *
     * @return constructed Iso8583Message
     * @throws IllegalStateException if MTI is not set
     */
    public Iso8583Message build() {
        if (mti == null) {
            throw new IllegalStateException("MTI must be set before building the message");
        }
        return new Iso8583Message(mti, fields, bitmap);
    }
}
