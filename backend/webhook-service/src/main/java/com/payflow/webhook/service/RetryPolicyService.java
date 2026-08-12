package com.payflow.webhook.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Retry policy for webhook delivery using exponential backoff.
 * Intervals: 5min, 30min, 2hr, 24hr — max 4 retries.
 */
@Service
public class RetryPolicyService {

    private static final int MAX_RETRIES = 4;

    /**
     * Retry intervals in order: 5 minutes, 30 minutes, 2 hours, 24 hours.
     */
    private static final Duration[] RETRY_INTERVALS = {
            Duration.ofMinutes(5),
            Duration.ofMinutes(30),
            Duration.ofHours(2),
            Duration.ofHours(24)
    };

    /**
     * Get the next retry time based on the current attempt count.
     *
     * @param currentAttempts number of attempts already made
     * @return the next retry instant, or null if max retries are exhausted
     */
    public Instant getNextRetryTime(int currentAttempts) {
        if (currentAttempts >= MAX_RETRIES) {
            return null; // No more retries
        }

        int retryIndex = Math.min(currentAttempts, RETRY_INTERVALS.length - 1);
        return Instant.now().plus(RETRY_INTERVALS[retryIndex]);
    }

    /**
     * Check if more retries are available.
     *
     * @param currentAttempts number of attempts already made
     * @return true if retries remain
     */
    public boolean hasRetriesRemaining(int currentAttempts) {
        return currentAttempts < MAX_RETRIES;
    }

    /**
     * Get the maximum number of retries.
     */
    public int getMaxRetries() {
        return MAX_RETRIES;
    }
}
