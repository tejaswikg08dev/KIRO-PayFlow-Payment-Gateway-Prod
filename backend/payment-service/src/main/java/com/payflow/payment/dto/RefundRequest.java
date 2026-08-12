package com.payflow.payment.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request DTO for creating a refund.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundRequest {

    @NotBlank(message = "Payment ID is required")
    private String paymentId;

    @NotNull(message = "Refund amount is required")
    @DecimalMin(value = "0.01", message = "Refund amount must be at least 0.01")
    @Digits(integer = 15, fraction = 4, message = "Amount format is invalid")
    private BigDecimal amount;

    @Size(max = 500, message = "Reason must be at most 500 characters")
    private String reason;
}
