package com.payflow.webhook.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RetryPolicyService Unit Tests")
class RetryPolicyServiceTest {

    private RetryPolicyService retryPolicyService;

    @BeforeEach
    void setUp() {
        retryPolicyService = new RetryPolicyService();
    }

    @Test
    @DisplayName("getNextRetryTime - attempt 1 should schedule retry in ~5 minutes")
    void getNextRetryTime_Attempt1_Returns5Minutes() {
        Instant before = Instant.now();
        Instant retryTime = retryPolicyService.getNextRetryTime(0); // 0 attempts made → first retry
        Instant after = Instant.now();

        assertThat(retryTime).isNotNull();

        // Should be approximately 5 minutes from now (within a small tolerance)
        Duration expectedDelay = Duration.ofMinutes(5);
        assertThat(retryTime).isAfterOrEqualTo(before.plus(expectedDelay).minusSeconds(1));
        assertThat(retryTime).isBeforeOrEqualTo(after.plus(expectedDelay).plusSeconds(1));
    }

    @Test
    @DisplayName("getNextRetryTime - attempt 2 should schedule retry in ~30 minutes")
    void getNextRetryTime_Attempt2_Returns30Minutes() {
        Instant before = Instant.now();
        Instant retryTime = retryPolicyService.getNextRetryTime(1); // 1 attempt made → second retry

        assertThat(retryTime).isNotNull();

        Duration expectedDelay = Duration.ofMinutes(30);
        assertThat(retryTime).isAfterOrEqualTo(before.plus(expectedDelay).minusSeconds(1));
    }

    @Test
    @DisplayName("getNextRetryTime - attempt 3 should schedule retry in ~2 hours")
    void getNextRetryTime_Attempt3_Returns2Hours() {
        Instant before = Instant.now();
        Instant retryTime = retryPolicyService.getNextRetryTime(2); // 2 attempts made → third retry

        assertThat(retryTime).isNotNull();

        Duration expectedDelay = Duration.ofHours(2);
        assertThat(retryTime).isAfterOrEqualTo(before.plus(expectedDelay).minusSeconds(1));
    }

    @Test
    @DisplayName("getNextRetryTime - attempt 4 should schedule retry in ~24 hours")
    void getNextRetryTime_Attempt4_Returns24Hours() {
        Instant before = Instant.now();
        Instant retryTime = retryPolicyService.getNextRetryTime(3); // 3 attempts made → fourth retry

        assertThat(retryTime).isNotNull();

        Duration expectedDelay = Duration.ofHours(24);
        assertThat(retryTime).isAfterOrEqualTo(before.plus(expectedDelay).minusSeconds(1));
    }

    @Test
    @DisplayName("getNextRetryTime - should return null after max retries exhausted")
    void getNextRetryTime_MaxRetriesExhausted_ReturnsNull() {
        Instant retryTime = retryPolicyService.getNextRetryTime(4); // 4 attempts = max

        assertThat(retryTime).isNull();
    }

    @Test
    @DisplayName("hasRetriesRemaining - should return true when retries remain")
    void hasRetriesRemaining_UnderMax_ReturnsTrue() {
        assertThat(retryPolicyService.hasRetriesRemaining(0)).isTrue();
        assertThat(retryPolicyService.hasRetriesRemaining(1)).isTrue();
        assertThat(retryPolicyService.hasRetriesRemaining(2)).isTrue();
        assertThat(retryPolicyService.hasRetriesRemaining(3)).isTrue();
    }

    @Test
    @DisplayName("hasRetriesRemaining - should return false after max attempts")
    void hasRetriesRemaining_AtMax_ReturnsFalse() {
        assertThat(retryPolicyService.hasRetriesRemaining(4)).isFalse();
        assertThat(retryPolicyService.hasRetriesRemaining(5)).isFalse();
        assertThat(retryPolicyService.hasRetriesRemaining(10)).isFalse();
    }

    @Test
    @DisplayName("getMaxRetries - should return 4")
    void getMaxRetries_Returns4() {
        assertThat(retryPolicyService.getMaxRetries()).isEqualTo(4);
    }
}
