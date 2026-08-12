package com.payflow.payment.model;

import com.payflow.common.constant.OrderStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents a payment order created by a merchant.
 * An order can have multiple payment attempts but only one successful payment.
 */
@Entity
@Table(name = "orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 30)
    private String id;

    @Column(name = "merchant_id", nullable = false, length = 30)
    private String merchantId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "customer_email", length = 255)
    private String customerEmail;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "receipt_number", length = 100)
    private String receiptNumber;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
