package com.payflow.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO representing an order.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {

    private String id;
    private String merchantId;
    private BigDecimal amount;
    private String currency;
    private String status;
    private String customerEmail;
    private String description;
    private String receiptNumber;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;
}
