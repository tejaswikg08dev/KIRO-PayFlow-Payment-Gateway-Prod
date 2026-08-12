package com.payflow.settlement.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "settlement_records")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id;

    @Column(name = "batch_id", nullable = false)
    private String batchId;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "gross_amount", precision = 19, scale = 4)
    private BigDecimal grossAmount;

    @Column(name = "refund_amount", precision = 19, scale = 4)
    private BigDecimal refundAmount;

    @Column(name = "mdr_amount", precision = 19, scale = 4)
    private BigDecimal mdrAmount;

    @Column(name = "gst_amount", precision = 19, scale = 4)
    private BigDecimal gstAmount;

    @Column(name = "net_amount", precision = 19, scale = 4)
    private BigDecimal netAmount;

    @Column(name = "payment_count")
    private int paymentCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
