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
public class ApiKeyResponse {

    private UUID id;
    private String prefix;
    private UUID merchantId;
    private Boolean active;
    private Instant createdAt;

    /**
     * The raw API key is only returned once at creation time.
     * After that, only the prefix is available.
     */
    private String rawKey;
}
