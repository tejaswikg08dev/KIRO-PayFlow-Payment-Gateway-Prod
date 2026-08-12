package com.payflow.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO representing a refund.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundResponse {

    private String id;
    private String paymentId;
    private String merchantId;
    private BigDecimal amount;
    private String reason;
    private String status;
    private Instant createdAt;
}
