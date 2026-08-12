package com.payflow.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for authorizing a payment against an order.
 * One of card, UPI, or net banking details must be provided based on paymentMethod.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthorizePaymentRequest {

    @NotBlank(message = "Order ID is required")
    private String orderId;

    @NotBlank(message = "Payment method is required")
    private String paymentMethod;

    // --- Card details (when paymentMethod = CARD) ---
    @Size(min = 13, max = 19, message = "Card number must be between 13 and 19 digits")
    private String cardNumber;

    @Size(min = 3, max = 4, message = "CVV must be 3 or 4 digits")
    private String cardCvv;

    @Size(min = 2, max = 2, message = "Card expiry month must be 2 digits")
    private String cardExpiryMonth;

    @Size(min = 4, max = 4, message = "Card expiry year must be 4 digits")
    private String cardExpiryYear;

    private String cardHolderName;

    // --- UPI details (when paymentMethod = UPI) ---
    private String upiId;

    // --- Net Banking details (when paymentMethod = NET_BANKING) ---
    private String bankCode;
    private String bankName;
}
