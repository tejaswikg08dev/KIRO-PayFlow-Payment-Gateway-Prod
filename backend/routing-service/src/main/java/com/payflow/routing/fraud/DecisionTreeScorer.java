package com.payflow.routing.fraud;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Simple decision-tree-based fraud scorer.
 * Uses extracted features to produce a risk score from 0-100.
 * <p>
 * This is a simplified implementation that mimics a decision tree
 * using weighted feature evaluation.
 */
@Component
public class DecisionTreeScorer {

    private static final Logger log = LoggerFactory.getLogger(DecisionTreeScorer.class);

    // Feature weights for scoring
    private static final double WEIGHT_HIGH_AMOUNT = 25.0;
    private static final double WEIGHT_NIGHT_TRANSACTION = 15.0;
    private static final double WEIGHT_INTERNATIONAL = 10.0;
    private static final double WEIGHT_ROUND_AMOUNT = 8.0;
    private static final double WEIGHT_CARD_BIN_RISK = 20.0;
    private static final double WEIGHT_PAYMENT_METHOD_RISK = 12.0;
    private static final double WEIGHT_AMOUNT_NORMALIZED = 10.0;

    /**
     * Scores a transaction based on extracted features.
     *
     * @param features Map of feature name to numeric value
     * @return Score from 0 to 100
     */
    public ScoringResult score(Map<String, Double> features) {
        double totalScore = 0.0;
        List<String> reasons = new ArrayList<>();

        // Decision Node 1: High amount check
        Double isHighAmount = features.getOrDefault(FraudFeatureExtractor.FEATURE_IS_HIGH_AMOUNT, 0.0);
        if (isHighAmount > 0) {
            totalScore += WEIGHT_HIGH_AMOUNT;
            reasons.add("High transaction amount detected");
        }

        // Decision Node 2: Night transaction check
        Double isNightTxn = features.getOrDefault(FraudFeatureExtractor.FEATURE_IS_NIGHT_TRANSACTION, 0.0);
        if (isNightTxn > 0) {
            totalScore += WEIGHT_NIGHT_TRANSACTION;
            reasons.add("Transaction during high-risk hours (11PM-5AM)");
        }

        // Decision Node 3: International transaction
        Double isInternational = features.getOrDefault(FraudFeatureExtractor.FEATURE_IS_INTERNATIONAL, 0.0);
        if (isInternational > 0) {
            totalScore += WEIGHT_INTERNATIONAL;
            reasons.add("International transaction");
        }

        // Decision Node 4: Round amount (suspicious pattern)
        Double isRoundAmount = features.getOrDefault(FraudFeatureExtractor.FEATURE_IS_ROUND_AMOUNT, 0.0);
        if (isRoundAmount > 0) {
            totalScore += WEIGHT_ROUND_AMOUNT;
            reasons.add("Suspiciously round amount");
        }

        // Decision Node 5: Card BIN risk (continuous)
        Double binRisk = features.getOrDefault(FraudFeatureExtractor.FEATURE_CARD_BIN_RISK, 0.0);
        double binContribution = binRisk * WEIGHT_CARD_BIN_RISK;
        totalScore += binContribution;
        if (binRisk > 0.5) {
            reasons.add("High-risk card BIN category");
        }

        // Decision Node 6: Payment method risk (continuous)
        Double methodRisk = features.getOrDefault(FraudFeatureExtractor.FEATURE_PAYMENT_METHOD_RISK, 0.0);
        double methodContribution = methodRisk * WEIGHT_PAYMENT_METHOD_RISK;
        totalScore += methodContribution;
        if (methodRisk > 0.5) {
            reasons.add("High-risk payment method");
        }

        // Decision Node 7: Normalized amount contribution
        Double amountNormalized = features.getOrDefault(FraudFeatureExtractor.FEATURE_AMOUNT_NORMALIZED, 0.0);
        totalScore += amountNormalized * WEIGHT_AMOUNT_NORMALIZED;

        // Clamp score to 0-100
        int finalScore = (int) Math.min(Math.max(totalScore, 0), 100);

        log.debug("Decision tree score: {} (raw: {:.2f}), reasons: {}", finalScore, totalScore, reasons.size());

        return new ScoringResult(finalScore, reasons);
    }

    /**
     * Scoring result with score and contributing factors.
     */
    public record ScoringResult(int score, List<String> reasons) {
    }
}
