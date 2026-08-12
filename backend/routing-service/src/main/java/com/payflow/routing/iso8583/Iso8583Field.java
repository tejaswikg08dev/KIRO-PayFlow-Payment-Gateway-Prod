package com.payflow.routing.iso8583;

/**
 * Defines an ISO 8583 field with its metadata.
 *
 * @param number    Field number (1-128)
 * @param name      Human-readable field name
 * @param type      Data type (NUMERIC, ALPHA, LLVAR, LLLVAR)
 * @param maxLength Maximum length of the field value
 */
public record Iso8583Field(
        int number,
        String name,
        FieldType type,
        int maxLength
) {

    /**
     * ISO 8583 field data types.
     */
    public enum FieldType {
        /** Fixed-length numeric field */
        NUMERIC,
        /** Fixed-length alphanumeric field */
        ALPHA,
        /** Variable-length field with 2-digit length prefix (LL) */
        LLVAR,
        /** Variable-length field with 3-digit length prefix (LLL) */
        LLLVAR
    }

    /**
     * Validates the field value against the field definition.
     */
    public boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        if (value.length() > maxLength) {
            return false;
        }
        if (type == FieldType.NUMERIC) {
            return value.chars().allMatch(Character::isDigit);
        }
        return true;
    }

    /**
     * Returns the byte length required to encode the field value.
     */
    public int getEncodedLength(String value) {
        if (value == null) return 0;
        return switch (type) {
            case NUMERIC, ALPHA -> maxLength;
            case LLVAR -> 2 + value.length();
            case LLLVAR -> 3 + value.length();
        };
    }
}
