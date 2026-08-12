package com.payflow.settlement.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "settlement_batches")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementBatch {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(name = "total_gross", precision = 19, scale = 4)
    private BigDecimal totalGross;

    @Column(name = "total_refunds", precision = 19, scale = 4)
    private BigDecimal totalRefunds;

    @Column(name = "total_mdr", precision = 19, scale = 4)
    private BigDecimal totalMdr;

    @Column(name = "total_gst", precision = 19, scale = 4)
    private BigDecimal totalGst;

    @Column(name = "total_net", precision = 19, scale = 4)
    private BigDecimal totalNet;

    @Column(name = "record_count")
    private int recordCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BatchStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public enum BatchStatus {
        PENDING, PROCESSING, COMPLETED, FAILED
    }
}
