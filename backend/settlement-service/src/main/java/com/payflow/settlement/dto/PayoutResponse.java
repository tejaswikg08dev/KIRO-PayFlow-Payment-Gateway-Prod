package com.payflow.settlement.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayoutResponse {

    private String id;
    private String merchantId;
    private String settlementRecordId;
    private BigDecimal amount;
    private String bankAccountNumber;
    private String bankIfsc;
    private String status;
    private Instant createdAt;
}
