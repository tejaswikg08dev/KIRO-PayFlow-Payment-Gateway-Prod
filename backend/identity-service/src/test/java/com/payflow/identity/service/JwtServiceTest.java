package com.payflow.identity.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtService Unit Tests")
class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        // Set jwt.secret field via reflection (minimum 256-bit key for HMAC-SHA)
        ReflectionTestUtils.setField(jwtService, "jwtSecret",
                "payflow-test-secret-key-that-is-at-least-32-bytes-long-for-hmac");
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", 3600000L); // 1 hour
        ReflectionTestUtils.setField(jwtService, "refreshTokenExpiration", 604800000L); // 7 days
    }

    @Test
    @DisplayName("generateAccessToken - should return a non-null JWT string")
    void generateAccessToken_ReturnsNonNull() {
        String token = jwtService.generateAccessToken("user-123", "john@example.com", "USER");

        assertThat(token).isNotNull();
        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3); // JWT has 3 parts: header.payload.signature
    }

    @Test
    @DisplayName("extractUserId - should return the subject from the token")
    void extractUserId_ReturnsCorrectSubject() {
        String token = jwtService.generateAccessToken("user-456", "jane@example.com", "MERCHANT");

        String userId = jwtService.extractUserId(token);

        assertThat(userId).isEqualTo("user-456");
    }

    @Test
    @DisplayName("isTokenValid - should return true for a valid non-expired token")
    void isTokenValid_WithValidToken_ReturnsTrue() {
        String token = jwtService.generateAccessToken("user-789", "admin@example.com", "ADMIN");

        boolean isValid = jwtService.isTokenValid(token);

        assertThat(isValid).isTrue();
    }

    @Test
    @DisplayName("isTokenValid - should return false for an expired token")
    void isTokenValid_WithExpiredToken_ReturnsFalse() {
        // Set expiration to -1ms (already expired)
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", -1000L);

        String token = jwtService.generateAccessToken("user-expired", "expired@example.com", "USER");

        boolean isValid = jwtService.isTokenValid(token);

        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("extractClaims - should contain email and role in claims")
    void extractClaims_ContainsCustomClaims() {
        String token = jwtService.generateAccessToken("user-claims", "test@example.com", "MERCHANT");

        var claims = jwtService.extractClaims(token);

        assertThat(claims.getSubject()).isEqualTo("user-claims");
        assertThat(claims.get("email", String.class)).isEqualTo("test@example.com");
        assertThat(claims.get("role", String.class)).isEqualTo("MERCHANT");
    }

    @Test
    @DisplayName("isTokenValid - should return false for a tampered token")
    void isTokenValid_WithTamperedToken_ReturnsFalse() {
        String token = jwtService.generateAccessToken("user-123", "john@example.com", "USER");
        String tamperedToken = token.substring(0, token.length() - 5) + "XXXXX";

        boolean isValid = jwtService.isTokenValid(tamperedToken);

        assertThat(isValid).isFalse();
    }
}
