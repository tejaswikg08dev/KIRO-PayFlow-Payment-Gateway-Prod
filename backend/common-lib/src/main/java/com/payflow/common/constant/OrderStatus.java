package com.payflow.common.constant;

/**
 * Order lifecycle states.
 * CREATED → ATTEMPTED → PAID → EXPIRED
 */
public enum OrderStatus {
    CREATED,    // Order created, awaiting payment attempt
    ATTEMPTED,  // Payment attempt initiated
    PAID,       // Payment captured successfully
    EXPIRED     // Order expired (no payment within time window)
}
