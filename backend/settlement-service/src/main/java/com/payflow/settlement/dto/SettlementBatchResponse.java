package com.payflow.settlement.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementBatchResponse {

    private String id;
    private LocalDate settlementDate;
    private BigDecimal totalGross;
    private BigDecimal totalRefunds;
    private BigDecimal totalMdr;
    private BigDecimal totalGst;
    private BigDecimal totalNet;
    private int recordCount;
    private String status;
    private Instant createdAt;
}
