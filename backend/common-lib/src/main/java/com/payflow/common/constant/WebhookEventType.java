package com.payflow.common.constant;

/**
 * Types of events that can be delivered via webhooks to merchants.
 */
public enum WebhookEventType {
    PAYMENT_AUTHORIZED("payment.authorized"),
    PAYMENT_CAPTURED("payment.captured"),
    PAYMENT_FAILED("payment.failed"),
    PAYMENT_REFUNDED("payment.refunded"),
    SETTLEMENT_COMPLETED("settlement.completed"),
    SETTLEMENT_PAYOUT("settlement.payout");

    private final String value;

    WebhookEventType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static WebhookEventType fromValue(String value) {
        for (WebhookEventType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown webhook event type: " + value);
    }
}
