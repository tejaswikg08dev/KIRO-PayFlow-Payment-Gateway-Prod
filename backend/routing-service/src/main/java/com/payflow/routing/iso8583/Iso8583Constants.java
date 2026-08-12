package com.payflow.routing.iso8583;

import java.util.HashMap;
import java.util.Map;

/**
 * ISO 8583 constants including MTI codes, field numbers, and field definitions.
 */
public final class Iso8583Constants {

    private Iso8583Constants() {
        // Utility class
    }

    // ==================== MTI Codes ====================

    /** Authorization Request */
    public static final String MTI_AUTH_REQUEST = "0100";

    /** Authorization Response */
    public static final String MTI_AUTH_RESPONSE = "0110";

    /** Financial Transaction Request (Purchase) */
    public static final String MTI_FINANCIAL_REQUEST = "0200";

    /** Financial Transaction Response */
    public static final String MTI_FINANCIAL_RESPONSE = "0210";

    /** Reversal Request */
    public static final String MTI_REVERSAL_REQUEST = "0420";

    /** Reversal Response */
    public static final String MTI_REVERSAL_RESPONSE = "0430";

    // ==================== Field Numbers ====================

    /** Primary Account Number (PAN) */
    public static final int FIELD_PAN = 2;

    /** Processing Code */
    public static final int FIELD_PROCESSING_CODE = 3;

    /** Transaction Amount */
    public static final int FIELD_AMOUNT = 4;

    /** System Trace Audit Number */
    public static final int FIELD_TRACE = 11;

    /** Transaction Time (hhmmss) */
    public static final int FIELD_TIME = 12;

    /** Authorization Code */
    public static final int FIELD_AUTH_CODE = 38;

    /** Response Code */
    public static final int FIELD_RESPONSE_CODE = 39;

    /** Terminal ID */
    public static final int FIELD_TERMINAL_ID = 41;

    /** Merchant Name / Location */
    public static final int FIELD_MERCHANT_NAME = 43;

    /** Currency Code */
    public static final int FIELD_CURRENCY_CODE = 49;

    // ==================== Response Codes ====================

    /** Approved */
    public static final String RESPONSE_APPROVED = "00";

    /** Declined - Do Not Honor */
    public static final String RESPONSE_DECLINED = "05";

    /** Insufficient Funds */
    public static final String RESPONSE_INSUFFICIENT_FUNDS = "51";

    /** Expired Card */
    public static final String RESPONSE_EXPIRED_CARD = "54";

    /** Suspected Fraud */
    public static final String RESPONSE_SUSPECTED_FRAUD = "59";

    /** System Error */
    public static final String RESPONSE_SYSTEM_ERROR = "96";

    // ==================== Processing Codes ====================

    /** Purchase */
    public static final String PROC_CODE_PURCHASE = "000000";

    /** Cash Advance */
    public static final String PROC_CODE_CASH_ADVANCE = "010000";

    /** Refund */
    public static final String PROC_CODE_REFUND = "200000";

    /**
     * Returns the complete map of ISO 8583 field definitions.
     */
    public static Map<Integer, Iso8583Field> getFieldDefinitions() {
        Map<Integer, Iso8583Field> definitions = new HashMap<>();

        definitions.put(FIELD_PAN, new Iso8583Field(
                FIELD_PAN, "Primary Account Number", Iso8583Field.FieldType.LLVAR, 19));

        definitions.put(FIELD_PROCESSING_CODE, new Iso8583Field(
                FIELD_PROCESSING_CODE, "Processing Code", Iso8583Field.FieldType.NUMERIC, 6));

        definitions.put(FIELD_AMOUNT, new Iso8583Field(
                FIELD_AMOUNT, "Transaction Amount", Iso8583Field.FieldType.NUMERIC, 12));

        definitions.put(FIELD_TRACE, new Iso8583Field(
                FIELD_TRACE, "System Trace Audit Number", Iso8583Field.FieldType.NUMERIC, 6));

        definitions.put(FIELD_TIME, new Iso8583Field(
                FIELD_TIME, "Transaction Time", Iso8583Field.FieldType.NUMERIC, 6));

        definitions.put(FIELD_AUTH_CODE, new Iso8583Field(
                FIELD_AUTH_CODE, "Authorization Code", Iso8583Field.FieldType.ALPHA, 6));

        definitions.put(FIELD_RESPONSE_CODE, new Iso8583Field(
                FIELD_RESPONSE_CODE, "Response Code", Iso8583Field.FieldType.ALPHA, 2));

        definitions.put(FIELD_TERMINAL_ID, new Iso8583Field(
                FIELD_TERMINAL_ID, "Card Acceptor Terminal ID", Iso8583Field.FieldType.ALPHA, 8));

        definitions.put(FIELD_MERCHANT_NAME, new Iso8583Field(
                FIELD_MERCHANT_NAME, "Card Acceptor Name/Location", Iso8583Field.FieldType.LLLVAR, 40));

        definitions.put(FIELD_CURRENCY_CODE, new Iso8583Field(
                FIELD_CURRENCY_CODE, "Transaction Currency Code", Iso8583Field.FieldType.NUMERIC, 3));

        return definitions;
    }
}
