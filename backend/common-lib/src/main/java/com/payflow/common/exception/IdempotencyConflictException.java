package com.payflow.common.exception;

/**
 * Thrown when an idempotency key conflict is detected.
 * This means the same request is already being processed or was already completed.
 * Maps to HTTP 409 Conflict.
 */
public class IdempotencyConflictException extends PayflowException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("IDEMPOTENCY_CONFLICT",
                String.format("Request with idempotency key '%s' is already being processed or was completed", idempotencyKey));
    }
}
