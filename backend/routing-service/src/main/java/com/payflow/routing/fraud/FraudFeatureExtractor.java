package com.payflow.routing.fraud;

import com.payflow.routing.dto.RoutingRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Extracts features from transaction data for fraud scoring.
 * Features include amount characteristics, time patterns, card info, and merchant data.
 */
@Component
public class FraudFeatureExtractor {

    private static final Logger log = LoggerFactory.getLogger(FraudFeatureExtractor.class);

    // Feature keys
    public static final String FEATURE_AMOUNT = "amount";
    public static final String FEATURE_AMOUNT_NORMALIZED = "amount_normalized";
    public static final String FEATURE_IS_HIGH_AMOUNT = "is_high_amount";
    public static final String FEATURE_IS_ROUND_AMOUNT = "is_round_amount";
    public static final String FEATURE_IS_NIGHT_TRANSACTION = "is_night_transaction";
    public static final String FEATURE_HOUR_OF_DAY = "hour_of_day";
    public static final String FEATURE_IS_INTERNATIONAL = "is_international";
    public static final String FEATURE_CARD_BIN_RISK = "card_bin_risk";
    public static final String FEATURE_PAYMENT_METHOD_RISK = "payment_method_risk";

    /**
     * Extracts a feature map from the routing request.
     *
     * @param request Transaction routing request
     * @return Map of feature name to numeric value
     */
    public Map<String, Double> extractFeatures(RoutingRequest request) {
        Map<String, Double> features = new HashMap<>();

        // Amount features
        BigDecimal amount = request.getAmount();
        features.put(FEATURE_AMOUNT, amount.doubleValue());
        features.put(FEATURE_AMOUNT_NORMALIZED, normalizeAmount(amount));
        features.put(FEATURE_IS_HIGH_AMOUNT, amount.compareTo(BigDecimal.valueOf(50000)) > 0 ? 1.0 : 0.0);
        features.put(FEATURE_IS_ROUND_AMOUNT, isRoundAmount(amount) ? 1.0 : 0.0);

        // Time features
        LocalTime now = LocalTime.now();
        int hour = now.getHour();
        features.put(FEATURE_HOUR_OF_DAY, (double) hour);
        features.put(FEATURE_IS_NIGHT_TRANSACTION, (hour >= 23 || hour <= 5) ? 1.0 : 0.0);

        // Currency / international features
        features.put(FEATURE_IS_INTERNATIONAL, isInternational(request.getCurrency()) ? 1.0 : 0.0);

        // Card BIN risk (simplified BIN-based risk scoring)
        features.put(FEATURE_CARD_BIN_RISK, calculateBinRisk(request.getCardBin()));

        // Payment method risk
        features.put(FEATURE_PAYMENT_METHOD_RISK, calculatePaymentMethodRisk(request.getPaymentMethod()));

        log.debug("Extracted {} features for merchant={}, amount={}",
                features.size(), request.getMerchantId(), amount);

        return features;
    }

    /**
     * Normalizes amount to 0-1 range (based on max expected value of 100,000).
     */
    private double normalizeAmount(BigDecimal amount) {
        double maxAmount = 100000.0;
        return Math.min(amount.doubleValue() / maxAmount, 1.0);
    }

    /**
     * Checks if amount is a round number (potential fraud indicator).
     */
    private boolean isRoundAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().scale() <= 0
                && amount.remainder(BigDecimal.valueOf(100)).compareTo(BigDecimal.ZERO) == 0;
    }

    /**
     * Checks if the transaction is international (non-domestic currency).
     */
    private boolean isInternational(String currency) {
        // Consider USD as domestic; everything else is international
        return currency != null && !"USD".equalsIgnoreCase(currency);
    }

    /**
     * Calculates risk score based on card BIN (first 6 digits).
     * Higher risk for unknown or prepaid BINs.
     */
    private double calculateBinRisk(String cardBin) {
        if (cardBin == null || cardBin.isEmpty()) {
            return 0.5; // Unknown BIN = moderate risk
        }
        // Simplified: prepaid card BINs (starting with 4, 5 are standard Visa/MC)
        if (cardBin.startsWith("6")) {
            return 0.6; // Discover/prepaid higher risk
        }
        if (cardBin.startsWith("4") || cardBin.startsWith("5")) {
            return 0.2; // Standard Visa/MC lower risk
        }
        return 0.4; // Default moderate risk
    }

    /**
     * Calculates risk based on payment method.
     */
    private double calculatePaymentMethodRisk(String paymentMethod) {
        if (paymentMethod == null) return 0.5;
        return switch (paymentMethod.toUpperCase()) {
            case "CREDIT_CARD" -> 0.3;
            case "DEBIT_CARD" -> 0.2;
            case "PREPAID" -> 0.7;
            case "WALLET" -> 0.4;
            default -> 0.5;
        };
    }
}
