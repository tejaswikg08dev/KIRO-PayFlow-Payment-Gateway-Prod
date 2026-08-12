package com.payflow.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.common.exception.IdempotencyConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Idempotency service using Redis SET NX EX pattern.
 * Ensures that duplicate requests with the same idempotency key
 * return the cached response instead of re-processing.
 *
 * Flow:
 * 1. Check if key exists → if yes, return cached response
 * 2. Lock key with SET NX EX (24h TTL)
 * 3. Execute the business logic
 * 4. Cache the result under the same key
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final String KEY_PREFIX = "idempotency:";
    private static final String LOCK_VALUE = "PROCESSING";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Checks if a cached response exists for the given idempotency key.
     *
     * @param idempotencyKey the client-provided idempotency key
     * @return cached response JSON if exists, empty otherwise
     */
    public Optional<String> getCachedResponse(String idempotencyKey) {
        String key = KEY_PREFIX + idempotencyKey;
        String value = redisTemplate.opsForValue().get(key);

        if (value == null) {
            return Optional.empty();
        }

        if (LOCK_VALUE.equals(value)) {
            // Request is currently being processed — conflict
            throw new IdempotencyConflictException(idempotencyKey);
        }

        log.debug("Idempotency cache hit for key: {}", idempotencyKey);
        return Optional.of(value);
    }

    /**
     * Attempts to acquire a processing lock for the given idempotency key.
     * Uses SET NX EX to atomically set the key only if it doesn't exist.
     *
     * @param idempotencyKey the client-provided idempotency key
     * @return true if lock was acquired, false if key already exists
     */
    public boolean acquireLock(String idempotencyKey) {
        String key = KEY_PREFIX + idempotencyKey;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, LOCK_VALUE, DEFAULT_TTL);
        
        if (Boolean.TRUE.equals(acquired)) {
            log.debug("Idempotency lock acquired for key: {}", idempotencyKey);
            return true;
        }

        return false;
    }

    /**
     * Caches the response for a successfully processed request.
     * Overwrites the PROCESSING lock value with the actual response.
     *
     * @param idempotencyKey the client-provided idempotency key
     * @param response the response object to cache
     */
    public <T> void cacheResponse(String idempotencyKey, T response) {
        String key = KEY_PREFIX + idempotencyKey;
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(key, json, DEFAULT_TTL);
            log.debug("Idempotency response cached for key: {}", idempotencyKey);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response for idempotency key: {}", idempotencyKey, e);
            // Remove the lock so the request can be retried
            redisTemplate.delete(key);
        }
    }

    /**
     * Releases the lock (used on failure to allow retry).
     *
     * @param idempotencyKey the client-provided idempotency key
     */
    public void releaseLock(String idempotencyKey) {
        String key = KEY_PREFIX + idempotencyKey;
        redisTemplate.delete(key);
        log.debug("Idempotency lock released for key: {}", idempotencyKey);
    }

    /**
     * Deserializes a cached JSON response to the target type.
     */
    public <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize cached response", e);
            throw new RuntimeException("Failed to deserialize cached response", e);
        }
    }
}
