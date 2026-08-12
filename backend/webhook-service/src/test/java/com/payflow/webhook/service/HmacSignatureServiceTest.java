package com.payflow.webhook.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HmacSignatureService Unit Tests")
class HmacSignatureServiceTest {

    private HmacSignatureService hmacSignatureService;

    private static final String TEST_SECRET = "whsec_test_secret_key_12345";
    private static final String TEST_PAYLOAD = "{\"event\":\"payment.captured\",\"paymentId\":\"pay-001\",\"amount\":10000}";

    @BeforeEach
    void setUp() {
        hmacSignatureService = new HmacSignatureService();
    }

    @Test
    @DisplayName("generateSignature - should produce a non-empty sha256 prefixed signature")
    void generateSignature_ProducesValidSignature() {
        String signature = hmacSignatureService.generateSignature(TEST_PAYLOAD, TEST_SECRET);

        assertThat(signature).isNotNull();
        assertThat(signature).isNotBlank();
        assertThat(signature).startsWith("sha256=");
        // SHA-256 produces 64 hex characters + "sha256=" prefix = 71 chars total
        assertThat(signature).hasSize(71);
    }

    @Test
    @DisplayName("generateSignature - same payload and secret produce consistent signature")
    void generateSignature_IsDeterministic() {
        String signature1 = hmacSignatureService.generateSignature(TEST_PAYLOAD, TEST_SECRET);
        String signature2 = hmacSignatureService.generateSignature(TEST_PAYLOAD, TEST_SECRET);

        assertThat(signature1).isEqualTo(signature2);
    }

    @Test
    @DisplayName("verifySignature - should return true for matching signature")
    void verifySignature_ValidSignature_ReturnsTrue() {
        String signature = hmacSignatureService.generateSignature(TEST_PAYLOAD, TEST_SECRET);

        boolean isValid = hmacSignatureService.verifySignature(TEST_PAYLOAD, TEST_SECRET, signature);

        assertThat(isValid).isTrue();
    }

    @Test
    @DisplayName("verifySignature - should return false for wrong secret")
    void verifySignature_WrongSecret_ReturnsFalse() {
        String signature = hmacSignatureService.generateSignature(TEST_PAYLOAD, TEST_SECRET);

        boolean isValid = hmacSignatureService.verifySignature(
                TEST_PAYLOAD, "wrong_secret_key", signature);

        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("verifySignature - should return false for tampered payload")
    void verifySignature_TamperedPayload_ReturnsFalse() {
        String signature = hmacSignatureService.generateSignature(TEST_PAYLOAD, TEST_SECRET);
        String tamperedPayload = TEST_PAYLOAD.replace("10000", "99999");

        boolean isValid = hmacSignatureService.verifySignature(
                tamperedPayload, TEST_SECRET, signature);

        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("generateSignature - empty secret should return empty string")
    void generateSignature_EmptySecret_ReturnsEmpty() {
        String signature = hmacSignatureService.generateSignature(TEST_PAYLOAD, "");

        assertThat(signature).isEmpty();
    }
}
