package com.payflow.common.exception;

/**
 * Thrown when attempting to create a resource that already exists.
 * Example: registering with an email already in use.
 * Maps to HTTP 409 Conflict.
 */
public class DuplicateResourceException extends PayflowException {

    public DuplicateResourceException(String resource, String field, String value) {
        super("DUPLICATE_RESOURCE",
                String.format("%s already exists with %s: %s", resource, field, value));
    }

    public DuplicateResourceException(String message) {
        super("DUPLICATE_RESOURCE", message);
    }
}
