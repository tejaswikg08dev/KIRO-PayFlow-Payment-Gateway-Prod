package com.payflow.merchant.service;

import com.payflow.common.exception.ResourceNotFoundException;
import com.payflow.merchant.dto.ApiKeyResponse;
import com.payflow.merchant.model.ApiKey;
import com.payflow.merchant.repository.ApiKeyRepository;
import com.payflow.merchant.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final MerchantRepository merchantRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generate a new API key for a merchant.
     * The raw key is returned only once; only the hash is stored.
     */
    @Transactional
    public ApiKeyResponse generateApiKey(UUID merchantId) {
        if (!merchantRepository.existsById(merchantId)) {
            throw new ResourceNotFoundException("Merchant", merchantId.toString());
        }

        // Generate a random 32-byte key
        byte[] keyBytes = new byte[32];
        secureRandom.nextBytes(keyBytes);
        String rawKey = "pk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes);

        // Compute SHA-256 hash
        String keyHash = sha256(rawKey);

        // Extract prefix (first 8 chars after pk_)
        String prefix = rawKey.substring(3, 11);

        ApiKey apiKey = ApiKey.builder()
                .keyHash(keyHash)
                .prefix(prefix)
                .merchantId(merchantId)
                .active(true)
                .build();

        ApiKey saved = apiKeyRepository.save(apiKey);

        log.info("API key generated for merchant: {}, prefix: {}", merchantId, prefix);

        return ApiKeyResponse.builder()
                .id(saved.getId())
                .prefix(saved.getPrefix())
                .merchantId(saved.getMerchantId())
                .active(saved.getActive())
                .createdAt(saved.getCreatedAt())
                .rawKey(rawKey)
                .build();
    }

    /**
     * Validate an API key. Returns the merchant ID if valid.
     */
    @Transactional(readOnly = true)
    public UUID validateApiKey(String rawKey) {
        String keyHash = sha256(rawKey);
        ApiKey apiKey = apiKeyRepository.findByKeyHash(keyHash)
                .orElseThrow(() -> new ResourceNotFoundException("API Key not found"));

        if (!apiKey.getActive()) {
            throw new ResourceNotFoundException("API Key has been revoked");
        }

        return apiKey.getMerchantId();
    }

    /**
     * List all API keys for a merchant (without raw key values).
     */
    @Transactional(readOnly = true)
    public List<ApiKeyResponse> getApiKeys(UUID merchantId) {
        return apiKeyRepository.findByMerchantId(merchantId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Revoke (deactivate) an API key.
     */
    @Transactional
    public void revokeApiKey(UUID keyId) {
        ApiKey apiKey = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new ResourceNotFoundException("API Key", keyId.toString()));
        apiKey.setActive(false);
        apiKeyRepository.save(apiKey);
        log.info("API key revoked: id={}, prefix={}", keyId, apiKey.getPrefix());
    }

    private ApiKeyResponse toResponse(ApiKey apiKey) {
        return ApiKeyResponse.builder()
                .id(apiKey.getId())
                .prefix(apiKey.getPrefix())
                .merchantId(apiKey.getMerchantId())
                .active(apiKey.getActive())
                .createdAt(apiKey.getCreatedAt())
                .rawKey(null) // Never expose stored keys
                .build();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
