package com.payflow.common.exception;

/**
 * Thrown when user is authenticated but lacks permission for the action.
 * Maps to HTTP 403 Forbidden.
 */
public class ForbiddenException extends PayflowException {

    public ForbiddenException(String message) {
        super("FORBIDDEN", message);
    }

    public ForbiddenException() {
        super("FORBIDDEN", "You do not have permission to perform this action");
    }
}
