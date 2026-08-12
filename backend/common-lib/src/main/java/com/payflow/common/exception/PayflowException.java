package com.payflow.common.exception;

import lombok.Getter;

/**
 * Base exception for all PayFlow application-specific errors.
 * All custom exceptions extend this class.
 */
@Getter
public class PayflowException extends RuntimeException {

    private final String errorCode;

    public PayflowException(String message) {
        super(message);
        this.errorCode = "PAYFLOW_ERROR";
    }

    public PayflowException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PayflowException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
