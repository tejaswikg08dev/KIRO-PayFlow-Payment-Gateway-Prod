package com.payflow.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO representing a payment.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {

    private String id;
    private String orderId;
    private String merchantId;
    private BigDecimal amount;
    private String currency;
    private String status;
    private String paymentMethod;
    private String authorizationCode;
    private String bankReferenceId;
    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;
}
