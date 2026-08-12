package com.payflow.common.exception;

import lombok.Getter;

/**
 * Thrown when a payment is declined by the bank/issuer.
 * Contains the decline reason code from the bank.
 * Maps to HTTP 402 Payment Required.
 */
@Getter
public class PaymentDeclinedException extends PayflowException {

    private final String declineCode;
    private final String declineReason;

    public PaymentDeclinedException(String declineCode, String declineReason) {
        super("PAYMENT_DECLINED",
                String.format("Payment declined: %s (%s)", declineReason, declineCode));
        this.declineCode = declineCode;
        this.declineReason = declineReason;
    }
}
