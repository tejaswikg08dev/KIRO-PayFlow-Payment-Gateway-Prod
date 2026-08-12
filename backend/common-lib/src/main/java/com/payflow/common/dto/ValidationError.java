package com.payflow.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Field-level validation error.
 * Used when request body fails Jakarta Bean Validation (e.g., @NotBlank, @Size).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationError {

    private String field;
    private String message;
    private Object rejectedValue;

    public static ValidationError of(String field, String message) {
        return ValidationError.builder()
                .field(field)
                .message(message)
                .build();
    }
}
