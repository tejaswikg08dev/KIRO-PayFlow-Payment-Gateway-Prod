package com.payflow.routing.service;

import com.payflow.routing.dto.RoutingRequest;
import com.payflow.routing.fraud.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fraud detection service that combines the RuleEngine and DecisionTreeScorer
 * to produce a comprehensive fraud assessment.
 * <p>
 * Scoring thresholds:
 * - Score < 30: APPROVE (low risk)
 * - Score 30-70: REVIEW (medium risk, needs manual review)
 * - Score > 70: DECLINE (high risk, block transaction)
 */
@Service
public class FraudDetectionService {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionService.class);

    private final RuleEngine ruleEngine;
    private final DecisionTreeScorer decisionTreeScorer;
    private final FraudFeatureExtractor featureExtractor;

    @Value("${fraud.weight.rules:0.6}")
    private double ruleWeight;

    @Value("${fraud.weight.model:0.4}")
    private double modelWeight;

    public FraudDetectionService(RuleEngine ruleEngine,
                                 DecisionTreeScorer decisionTreeScorer,
                                 FraudFeatureExtractor featureExtractor) {
        this.ruleEngine = ruleEngine;
        this.decisionTreeScorer = decisionTreeScorer;
        this.featureExtractor = featureExtractor;
    }

    /**
     * Performs comprehensive fraud analysis on a transaction.
     *
     * @param request The routing request to analyze
     * @return FraudResult with score, action, and reasons
     */
    public FraudResult analyze(RoutingRequest request) {
        log.debug("Starting fraud analysis for merchant={}, amount={}",
                request.getMerchantId(), request.getAmount());

        // Step 1: Rule engine evaluation
        RuleEngine.RuleResult ruleResult = ruleEngine.evaluate(request);

        // Step 2: Feature extraction and ML scoring
        Map<String, Double> features = featureExtractor.extractFeatures(request);
        DecisionTreeScorer.ScoringResult modelResult = decisionTreeScorer.score(features);

        // Step 3: Combine scores with weights
        double combinedScore = (ruleResult.score() * ruleWeight) + (modelResult.score() * modelWeight);
        int finalScore = (int) Math.min(Math.max(combinedScore, 0), 100);

        // Step 4: Aggregate reasons
        List<String> allReasons = new ArrayList<>();
        allReasons.addAll(ruleResult.violations());
        allReasons.addAll(modelResult.reasons());

        // Step 5: Determine action
        FraudResult result = FraudResult.of(finalScore, allReasons);

        log.info("Fraud analysis complete: score={}, action={}, reasons={} (rule={}, model={})",
                finalScore, result.action(), allReasons.size(),
                ruleResult.score(), modelResult.score());

        return result;
    }

    /**
     * Quick check for obvious fraud indicators (bypasses ML scoring).
     * Used for fast-path rejection of clearly fraudulent transactions.
     */
    public boolean isObviousFraud(RoutingRequest request) {
        RuleEngine.RuleResult ruleResult = ruleEngine.evaluate(request);
        return ruleResult.score() > 70;
    }
}
