package com.payflow.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Structured error response returned when an API call fails.
 * Contains error code, human-readable message, and optional field-level details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private String code;
    private String message;
    private List<ValidationError> details;

    public static ErrorResponse of(String code, String message) {
        return ErrorResponse.builder()
                .code(code)
                .message(message)
                .build();
    }

    public static ErrorResponse withDetails(String code, String message, List<ValidationError> details) {
        return ErrorResponse.builder()
                .code(code)
                .message(message)
                .details(details)
                .build();
    }
}
