package com.payflow.payment.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents a refund issued against a captured payment.
 * Supports both full and partial refunds.
 */
@Entity
@Table(name = "refunds")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Refund {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 30)
    private String id;

    @Column(name = "payment_id", nullable = false, length = 30)
    private String paymentId;

    @Column(name = "merchant_id", nullable = false, length = 30)
    private String merchantId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
