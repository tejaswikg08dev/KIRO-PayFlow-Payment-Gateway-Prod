package com.payflow.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.common.exception.IdempotencyConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyService Unit Tests")
class IdempotencyServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private IdempotencyService idempotencyService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        idempotencyService = new IdempotencyService(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("getCachedResponse - should return empty for a new idempotency key")
    void getCachedResponse_NewKey_ReturnsEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency:new-key-123")).thenReturn(null);

        Optional<String> result = idempotencyService.getCachedResponse("new-key-123");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getCachedResponse - should return cached JSON for existing key")
    void getCachedResponse_ExistingKey_ReturnsCachedResult() {
        String cachedJson = "{\"id\":\"pay-001\",\"status\":\"AUTHORIZED\"}";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency:existing-key")).thenReturn(cachedJson);

        Optional<String> result = idempotencyService.getCachedResponse("existing-key");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(cachedJson);
    }

    @Test
    @DisplayName("getCachedResponse - should throw IdempotencyConflictException for PROCESSING key")
    void getCachedResponse_ProcessingKey_ThrowsConflict() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency:processing-key")).thenReturn("PROCESSING");

        assertThatThrownBy(() -> idempotencyService.getCachedResponse("processing-key"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("acquireLock - should return true when key is successfully locked")
    void acquireLock_Success_ReturnsTrue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("idempotency:lock-key"), eq("PROCESSING"), any(Duration.class)))
                .thenReturn(true);

        boolean acquired = idempotencyService.acquireLock("lock-key");

        assertThat(acquired).isTrue();
    }

    @Test
    @DisplayName("acquireLock - should return false when key already exists")
    void acquireLock_AlreadyExists_ReturnsFalse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("idempotency:lock-key"), eq("PROCESSING"), any(Duration.class)))
                .thenReturn(false);

        boolean acquired = idempotencyService.acquireLock("lock-key");

        assertThat(acquired).isFalse();
    }

    @Test
    @DisplayName("releaseLock - should delete the key from Redis")
    void releaseLock_DeletesKey() {
        when(redisTemplate.delete("idempotency:release-key")).thenReturn(true);

        idempotencyService.releaseLock("release-key");

        verify(redisTemplate).delete("idempotency:release-key");
    }
}
