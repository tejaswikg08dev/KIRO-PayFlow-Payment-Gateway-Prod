package com.payflow.payment.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Stores payment method details (card, UPI, or net banking) for a payment.
 * Card details are stored in masked/tokenized form — never raw PAN.
 */
@Entity
@Table(name = "payment_methods")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMethodEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "payment_id", nullable = false, length = 30)
    private String paymentId;

    @Column(name = "type", nullable = false, length = 20)
    private String type;

    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "card_brand", length = 20)
    private String cardBrand;

    @Column(name = "card_expiry_month", length = 2)
    private String cardExpiryMonth;

    @Column(name = "card_expiry_year", length = 4)
    private String cardExpiryYear;

    @Column(name = "upi_id", length = 100)
    private String upiId;

    @Column(name = "bank_code", length = 20)
    private String bankCode;

    @Column(name = "bank_name", length = 100)
    private String bankName;
}
