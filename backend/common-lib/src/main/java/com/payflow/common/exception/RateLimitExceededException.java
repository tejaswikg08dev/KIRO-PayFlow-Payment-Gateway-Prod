package com.payflow.common.exception;

/**
 * Thrown when a client exceeds their rate limit (too many requests).
 * Maps to HTTP 429 Too Many Requests.
 */
public class RateLimitExceededException extends PayflowException {

    public RateLimitExceededException(String message) {
        super("RATE_LIMIT_EXCEEDED", message);
    }

    public RateLimitExceededException() {
        super("RATE_LIMIT_EXCEEDED", "Too many requests. Please try again later.");
    }
}
