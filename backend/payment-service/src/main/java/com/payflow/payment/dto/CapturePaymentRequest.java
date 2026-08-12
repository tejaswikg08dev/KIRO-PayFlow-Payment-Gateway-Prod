package com.payflow.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request DTO for capturing an authorized payment.
 * If amount is null, full authorized amount is captured.
 * If amount is provided, a partial capture is performed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapturePaymentRequest {

    @NotBlank(message = "Payment ID is required")
    private String paymentId;

    @DecimalMin(value = "0.01", message = "Capture amount must be at least 0.01")
    @Digits(integer = 15, fraction = 4, message = "Amount format is invalid")
    private BigDecimal amount;
}
