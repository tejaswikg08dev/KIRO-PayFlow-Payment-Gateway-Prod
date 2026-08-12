package com.payflow.merchant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantResponse {

    private UUID id;
    private String name;
    private String email;
    private String businessType;
    private Double mdrRate;
    private Boolean active;
    private Instant createdAt;
    private Instant updatedAt;
}
