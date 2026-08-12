package com.payflow.common.util;

/**
 * Utility for masking sensitive data (card numbers, emails).
 * Used to prevent full PAN exposure in logs and API responses.
 */
public final class MaskingUtils {

    private MaskingUtils() {
    }

    /**
     * Masks a card number showing only first 4 and last 4 digits.
     * Example: 4111111111111111 → 4111****1111
     */
    public static String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 8) {
            return "****";
        }
        String cleaned = cardNumber.replaceAll("\\s+", "");
        return cleaned.substring(0, 4) + "****" + cleaned.substring(cleaned.length() - 4);
    }

    /**
     * Masks an email address.
     * Example: john.doe@example.com → j***e@example.com
     */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "****";
        }
        String[] parts = email.split("@");
        String local = parts[0];
        if (local.length() <= 2) {
            return local.charAt(0) + "***@" + parts[1];
        }
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + "@" + parts[1];
    }

    /**
     * Masks a UPI ID.
     * Example: user@oksbi → u***r@oksbi
     */
    public static String maskUpiId(String upiId) {
        if (upiId == null || !upiId.contains("@")) {
            return "****";
        }
        String[] parts = upiId.split("@");
        String local = parts[0];
        if (local.length() <= 2) {
            return local.charAt(0) + "***@" + parts[1];
        }
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + "@" + parts[1];
    }
}
