package com.payflow.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Generic API response wrapper used by ALL services.
 * Every endpoint returns this structure for consistency.
 *
 * Success: {success: true, data: {...}, timestamp: "..."}
 * Error:   {success: false, error: {...}, timestamp: "..."}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private boolean success;
    private T data;
    private ErrorResponse error;

    @Builder.Default
    private Instant timestamp = Instant.now();

    private String path;

    /** Factory method for successful responses */
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .timestamp(Instant.now())
                .build();
    }

    /** Factory method for error responses */
    public static <T> ApiResponse<T> error(ErrorResponse error) {
        return ApiResponse.<T>builder()
                .success(false)
                .error(error)
                .timestamp(Instant.now())
                .build();
    }

    /** Factory method for error responses with path */
    public static <T> ApiResponse<T> error(ErrorResponse error, String path) {
        return ApiResponse.<T>builder()
                .success(false)
                .error(error)
                .path(path)
                .timestamp(Instant.now())
                .build();
    }
}
