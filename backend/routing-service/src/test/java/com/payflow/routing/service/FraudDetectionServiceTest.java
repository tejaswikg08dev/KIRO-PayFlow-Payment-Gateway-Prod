package com.payflow.routing.service;

import com.payflow.routing.dto.RoutingRequest;
import com.payflow.routing.fraud.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FraudDetectionService Unit Tests")
class FraudDetectionServiceTest {

    @Mock
    private RuleEngine ruleEngine;

    @Mock
    private DecisionTreeScorer decisionTreeScorer;

    @Mock
    private FraudFeatureExtractor featureExtractor;

    private FraudDetectionService fraudDetectionService;

    @BeforeEach
    void setUp() {
        fraudDetectionService = new FraudDetectionService(ruleEngine, decisionTreeScorer, featureExtractor);
        ReflectionTestUtils.setField(fraudDetectionService, "ruleWeight", 0.6);
        ReflectionTestUtils.setField(fraudDetectionService, "modelWeight", 0.4);
    }

    @Test
    @DisplayName("analyze - low amount transaction should result in APPROVE")
    void analyze_LowAmount_ReturnsApprove() {
        RoutingRequest request = new RoutingRequest(
                "merchant-001", new BigDecimal("500"), "INR", "CARD", "411111", null);

        // Rule engine gives low score (no violations)
        when(ruleEngine.evaluate(any(RoutingRequest.class)))
                .thenReturn(new RuleEngine.RuleResult(0, List.of()));

        // Feature extraction returns benign features
        when(featureExtractor.extractFeatures(any(RoutingRequest.class)))
                .thenReturn(Map.of());

        // Model gives low score
        when(decisionTreeScorer.score(any(Map.class)))
                .thenReturn(new DecisionTreeScorer.ScoringResult(10, List.of()));

        FraudResult result = fraudDetectionService.analyze(request);

        assertThat(result.score()).isLessThan(30);
        assertThat(result.action()).isEqualTo(FraudResult.FraudAction.APPROVE);
    }

    @Test
    @DisplayName("analyze - high velocity should produce high fraud score")
    void analyze_HighVelocity_ReturnsHighScore() {
        RoutingRequest request = new RoutingRequest(
                "merchant-001", new BigDecimal("2000"), "INR", "CARD", "411111", null);

        // Rule engine detects velocity issue - high score
        when(ruleEngine.evaluate(any(RoutingRequest.class)))
                .thenReturn(new RuleEngine.RuleResult(50, List.of("Velocity exceeded: >5 transactions in 1 minute")));

        when(featureExtractor.extractFeatures(any(RoutingRequest.class)))
                .thenReturn(Map.of());

        when(decisionTreeScorer.score(any(Map.class)))
                .thenReturn(new DecisionTreeScorer.ScoringResult(40, List.of("High-risk pattern detected")));

        FraudResult result = fraudDetectionService.analyze(request);

        // Combined: (50 * 0.6) + (40 * 0.4) = 30 + 16 = 46 → REVIEW
        assertThat(result.score()).isGreaterThanOrEqualTo(30);
        assertThat(result.reasons()).isNotEmpty();
    }

    @Test
    @DisplayName("analyze - huge amount should result in DECLINE")
    void analyze_HugeAmount_ReturnsDecline() {
        RoutingRequest request = new RoutingRequest(
                "merchant-001", new BigDecimal("1000000"), "INR", "CARD", "411111", null);

        // Rule engine detects extreme amount - very high score
        when(ruleEngine.evaluate(any(RoutingRequest.class)))
                .thenReturn(new RuleEngine.RuleResult(90, List.of("High amount: 1000000 exceeds threshold")));

        when(featureExtractor.extractFeatures(any(RoutingRequest.class)))
                .thenReturn(Map.of());

        // Model also flags as high risk
        when(decisionTreeScorer.score(any(Map.class)))
                .thenReturn(new DecisionTreeScorer.ScoringResult(80, List.of("High transaction amount detected")));

        FraudResult result = fraudDetectionService.analyze(request);

        // Combined: (90 * 0.6) + (80 * 0.4) = 54 + 32 = 86 → DECLINE
        assertThat(result.score()).isGreaterThan(70);
        assertThat(result.action()).isEqualTo(FraudResult.FraudAction.DECLINE);
    }

    @Test
    @DisplayName("analyze - medium risk should result in REVIEW")
    void analyze_MediumRisk_ReturnsReview() {
        RoutingRequest request = new RoutingRequest(
                "merchant-001", new BigDecimal("60000"), "INR", "CARD", "411111", null);

        when(ruleEngine.evaluate(any(RoutingRequest.class)))
                .thenReturn(new RuleEngine.RuleResult(40, List.of("Moderate amount threshold")));

        when(featureExtractor.extractFeatures(any(RoutingRequest.class)))
                .thenReturn(Map.of());

        when(decisionTreeScorer.score(any(Map.class)))
                .thenReturn(new DecisionTreeScorer.ScoringResult(50, List.of("Night transaction")));

        FraudResult result = fraudDetectionService.analyze(request);

        // Combined: (40 * 0.6) + (50 * 0.4) = 24 + 20 = 44 → REVIEW
        assertThat(result.score()).isBetween(30, 70);
        assertThat(result.action()).isEqualTo(FraudResult.FraudAction.REVIEW);
    }

    @Test
    @DisplayName("analyze - should aggregate reasons from both rule engine and model")
    void analyze_AggregatesReasons() {
        RoutingRequest request = new RoutingRequest(
                "merchant-001", new BigDecimal("100000"), "INR", "CARD", "411111", null);

        when(ruleEngine.evaluate(any(RoutingRequest.class)))
                .thenReturn(new RuleEngine.RuleResult(30, List.of("Rule violation 1")));

        when(featureExtractor.extractFeatures(any(RoutingRequest.class)))
                .thenReturn(Map.of());

        when(decisionTreeScorer.score(any(Map.class)))
                .thenReturn(new DecisionTreeScorer.ScoringResult(20, List.of("Model reason 1", "Model reason 2")));

        FraudResult result = fraudDetectionService.analyze(request);

        assertThat(result.reasons()).hasSize(3);
        assertThat(result.reasons()).contains("Rule violation 1", "Model reason 1", "Model reason 2");
    }
}
