package com.payflow.common.exception;

/**
 * Thrown when a requested resource (payment, order, merchant) does not exist.
 * Maps to HTTP 404.
 */
public class ResourceNotFoundException extends PayflowException {

    public ResourceNotFoundException(String resource, String id) {
        super("RESOURCE_NOT_FOUND", String.format("%s not found with id: %s", resource, id));
    }

    public ResourceNotFoundException(String message) {
        super("RESOURCE_NOT_FOUND", message);
    }
}
