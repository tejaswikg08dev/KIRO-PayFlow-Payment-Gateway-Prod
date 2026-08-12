package com.payflow.routing.fraud;

import com.payflow.routing.dto.RoutingRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rule-based fraud detection engine.
 * Implements velocity checks, amount thresholds, and geo-blocking rules.
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    @Value("${fraud.rules.velocity-threshold:5}")
    private int velocityThreshold;

    @Value("${fraud.rules.amount-threshold:50000}")
    private double amountThreshold;

    @Value("${fraud.rules.velocity-window-ms:60000}")
    private long velocityWindowMs;

    // In-memory velocity tracking (merchantId -> list of transaction timestamps)
    private final Map<String, List<Long>> velocityMap = new ConcurrentHashMap<>();

    // Geo-blocked countries
    private static final List<String> BLOCKED_COUNTRIES = List.of(
            "KP", // North Korea
            "IR", // Iran
            "SY", // Syria
            "CU"  // Cuba
    );

    /**
     * Evaluates all rules against the transaction and returns a score contribution.
     *
     * @param request Transaction routing request
     * @return Score from 0-100 based on rule violations
     */
    public RuleResult evaluate(RoutingRequest request) {
        List<String> violations = new ArrayList<>();
        int score = 0;

        // Rule 1: Velocity check (>5 transactions per minute from same merchant)
        int velocityScore = checkVelocity(request.getMerchantId());
        if (velocityScore > 0) {
            score += velocityScore;
            violations.add(String.format("Velocity exceeded: >%d transactions in 1 minute", velocityThreshold));
        }

        // Rule 2: Amount threshold (>50K)
        int amountScore = checkAmountThreshold(request.getAmount());
        if (amountScore > 0) {
            score += amountScore;
            violations.add(String.format("High amount: %s exceeds threshold of %.0f",
                    request.getAmount(), amountThreshold));
        }

        // Rule 3: Geo-blocking check
        int geoScore = checkGeoBlocking(request.getCurrency());
        if (geoScore > 0) {
            score += geoScore;
            violations.add("Transaction from geo-blocked region");
        }

        // Ensure score is within bounds
        score = Math.min(score, 100);

        log.debug("Rule engine score for merchant={}: {} (violations: {})",
                request.getMerchantId(), score, violations.size());

        return new RuleResult(score, violations);
    }

    /**
     * Checks transaction velocity for the merchant.
     * Returns score contribution if velocity exceeds threshold.
     */
    private int checkVelocity(String merchantId) {
        if (merchantId == null) return 0;

        long now = System.currentTimeMillis();
        long windowStart = now - velocityWindowMs;

        velocityMap.computeIfAbsent(merchantId, k -> new ArrayList<>());
        List<Long> timestamps = velocityMap.get(merchantId);

        synchronized (timestamps) {
            // Remove expired timestamps
            timestamps.removeIf(ts -> ts < windowStart);
            // Add current transaction
            timestamps.add(now);

            if (timestamps.size() > velocityThreshold) {
                // Score increases with velocity
                int excess = timestamps.size() - velocityThreshold;
                return Math.min(excess * 15, 50); // Max 50 from velocity
            }
        }

        return 0;
    }

    /**
     * Checks if the transaction amount exceeds the threshold.
     */
    private int checkAmountThreshold(BigDecimal amount) {
        if (amount == null) return 0;

        if (amount.doubleValue() > amountThreshold) {
            // Higher amounts get higher scores
            double ratio = amount.doubleValue() / amountThreshold;
            if (ratio > 10) return 40;
            if (ratio > 5) return 30;
            if (ratio > 2) return 20;
            return 15;
        }
        return 0;
    }

    /**
     * Checks if the transaction originates from a geo-blocked region.
     * Uses currency code as a proxy for region in this simplified implementation.
     */
    private int checkGeoBlocking(String currency) {
        if (currency == null) return 0;

        // Map certain currencies to blocked countries
        String countryFromCurrency = mapCurrencyToCountry(currency);
        if (countryFromCurrency != null && BLOCKED_COUNTRIES.contains(countryFromCurrency)) {
            return 80; // Geo-blocked = very high score
        }
        return 0;
    }

    /**
     * Maps currency codes to country codes for geo-blocking purposes.
     */
    private String mapCurrencyToCountry(String currency) {
        return switch (currency.toUpperCase()) {
            case "KPW" -> "KP";
            case "IRR" -> "IR";
            case "SYP" -> "SY";
            case "CUP" -> "CU";
            default -> null;
        };
    }

    /**
     * Result from rule engine evaluation.
     */
    public record RuleResult(int score, List<String> violations) {
        public boolean hasViolations() {
            return !violations.isEmpty();
        }
    }
}
