package com.payflow.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Kafka event payload for payment state changes.
 * Published to topics: payment.authorized, payment.captured, payment.failed, payment.refunded
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentEvent {

    private String eventId;
    private String eventType;
    private String paymentId;
    private String orderId;
    private String merchantId;
    private BigDecimal amount;
    private String currency;
    private String status;
    private String paymentMethod;
    private Instant timestamp;
    private String metadata;
}
