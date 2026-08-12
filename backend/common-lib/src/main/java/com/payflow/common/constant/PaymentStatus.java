package com.payflow.common.constant;

/**
 * Payment lifecycle states (state machine).
 * CREATED → AUTHORIZED → CAPTURED → REFUNDED
 *                      → VOIDED
 * CREATED → FAILED
 */
public enum PaymentStatus {
    CREATED,     // Payment initiated, not yet sent to bank
    AUTHORIZED,  // Bank approved — money reserved but not collected
    CAPTURED,    // Money collected from customer's account
    REFUNDED,    // Money returned to customer (after capture)
    VOIDED,      // Authorization cancelled (before capture)
    FAILED       // Bank declined or error occurred
}
