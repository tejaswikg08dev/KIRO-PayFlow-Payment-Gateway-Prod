package com.payflow.common.util;

import java.util.UUID;

/**
 * Generates unique IDs for all PayFlow entities.
 * Format: prefix_uuid (e.g., pay_abc123, order_xyz789, mer_def456)
 */
public final class IdGenerator {

    private IdGenerator() {
        // Utility class — prevent instantiation
    }

    public static String generatePaymentId() {
        return "pay_" + shortUuid();
    }

    public static String generateOrderId() {
        return "order_" + shortUuid();
    }

    public static String generateMerchantId() {
        return "mer_" + shortUuid();
    }

    public static String generateRefundId() {
        return "rfnd_" + shortUuid();
    }

    public static String generateSettlementId() {
        return "stl_" + shortUuid();
    }

    public static String generatePayoutId() {
        return "pout_" + shortUuid();
    }

    public static String generateWebhookId() {
        return "whk_" + shortUuid();
    }

    public static String generateEventId() {
        return "evt_" + shortUuid();
    }

    public static String generateApiKeyId() {
        return "key_" + shortUuid();
    }

    /**
     * Generates a shortened UUID (first 12 chars, no dashes).
     * Provides sufficient uniqueness for this application.
     */
    private static String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * Generates a full UUID when maximum uniqueness is required.
     */
    public static String fullUuid() {
        return UUID.randomUUID().toString();
    }
}
