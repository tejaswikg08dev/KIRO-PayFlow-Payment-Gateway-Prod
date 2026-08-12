package com.payflow.webhook.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Service for generating HMAC-SHA256 signatures for webhook payloads.
 * Merchants verify the X-PayFlow-Signature header to ensure payload integrity.
 */
@Slf4j
@Service
public class HmacSignatureService {

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * Generate HMAC-SHA256 signature for a JSON payload using the merchant's webhook secret.
     *
     * @param payload the JSON payload string
     * @param secret  the merchant's webhook secret key
     * @return hex-encoded HMAC signature
     */
    public String generateSignature(String payload, String secret) {
        if (secret == null || secret.isBlank()) {
            log.warn("Webhook secret is empty, returning empty signature");
            return "";
        }

        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(secretKeySpec);

            byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(hmacBytes);
        } catch (Exception e) {
            log.error("Failed to generate HMAC signature: {}", e.getMessage(), e);
            throw new RuntimeException("HMAC signature generation failed", e);
        }
    }

    /**
     * Verify that a given signature matches the expected signature for the payload.
     *
     * @param payload           the JSON payload string
     * @param secret            the merchant's webhook secret key
     * @param providedSignature the signature to verify
     * @return true if signatures match
     */
    public boolean verifySignature(String payload, String secret, String providedSignature) {
        String expectedSignature = generateSignature(payload, secret);
        return expectedSignature.equals(providedSignature);
    }
}
