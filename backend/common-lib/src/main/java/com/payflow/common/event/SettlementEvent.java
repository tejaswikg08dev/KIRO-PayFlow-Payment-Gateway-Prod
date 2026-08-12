package com.payflow.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Kafka event payload for settlement triggers.
 * Published when a settlement batch completes or a payout is initiated.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementEvent {

    private String eventId;
    private String eventType;        // settlement.completed, settlement.payout
    private String batchId;
    private String merchantId;
    private BigDecimal grossAmount;
    private BigDecimal netAmount;
    private BigDecimal mdrAmount;
    private BigDecimal gstAmount;
    private LocalDate settlementDate;
    private String status;
    private Instant timestamp;
}
