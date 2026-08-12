package com.payflow.common.exception;

/**
 * Thrown when authentication fails (invalid credentials, expired token).
 * Maps to HTTP 401 Unauthorized.
 */
public class UnauthorizedException extends PayflowException {

    public UnauthorizedException(String message) {
        super("UNAUTHORIZED", message);
    }

    public UnauthorizedException() {
        super("UNAUTHORIZED", "Authentication required");
    }
}
