package com.payflow.common.constant;

/**
 * Supported payment methods in PayFlow.
 */
public enum PaymentMethod {
    CARD,         // Credit/Debit card (Visa, Mastercard, RuPay)
    UPI,          // Unified Payments Interface (India)
    NET_BANKING   // Internet banking (redirect to bank website)
}
