# 🏗️ Phase 4 Part 9g: Routing Service — Fraud ML Scorer + FraudResult + FraudDetectionService + Tests

> **"(ruleScore × 0.6) + (mlScore × 0.4) — one formula that combines human intuition (rules) with pattern recognition (ML) to catch fraud that neither can detect alone."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 9g — Fraud ML Scorer + FraudDetectionService + Tests |
| **What You Build** | DecisionTreeScorer.java, FraudResult.java, FraudDetectionService.java, FraudDetectionServiceTest.java |
| **Previous** | [Part 9f — Fraud Rule Engine](./phase4-part09f-fraud-rule-engine.md) |
| **Next** | [Part 9h — Smart Routing](./phase4-part09h-smart-routing.md) |

---

## 📖 Table of Contents

1. [The Complete Fraud Pipeline](#1-the-complete-fraud-pipeline)
2. [Folder Structure After This Part](#2-folder-structure-after-this-part)
3. [Step-by-Step: DecisionTreeScorer.java](#3-step-by-step-decisiontreescorerjava)
4. [Step-by-Step: FraudResult.java](#4-step-by-step-fraudresultjava)
5. [Step-by-Step: FraudDetectionService.java](#5-step-by-step-frauddetectionservicejava)
6. [Step-by-Step: FraudDetectionServiceTest.java](#6-step-by-step-frauddetectionservicetestjava)
7. [Scoring Examples — End to End](#7-scoring-examples--end-to-end)
8. [What You Learned](#8-what-you-learned)

---

## 1. The Complete Fraud Pipeline

Now that we have RuleEngine (Part 9f) and FraudFeatureExtractor (Part 9f), this part adds the ML scorer and the orchestrating service that combines them:

```
RoutingRequest
     │
     ├──────────────────────────┐
     │                          │
     ▼                          ▼
RuleEngine.evaluate()    FraudFeatureExtractor.extractFeatures()
     │                          │
     │ RuleResult               │ Map<String, Double>
     │ {score: 40,              │ {is_high_amount: 1.0, ...}
     │  violations: [...]}      │         │
     │                          │         ▼
     │                          │  DecisionTreeScorer.score() ← THIS PART
     │                          │         │
     │                          │  ScoringResult
     │                          │  {score: 50, reasons: [...]}
     │                          │
     ▼                          ▼
FraudDetectionService.analyze() ← THIS PART (orchestrator)
     │
     │ finalScore = (40 × 0.6) + (50 × 0.4) = 24 + 20 = 44
     │
     ▼
FraudResult ← THIS PART
     {score: 44, action: REVIEW, reasons: [...]}
```

---

## 2. Folder Structure After This Part

```
backend/routing-service/src/
├── main/java/com/payflow/routing/
│   ├── fraud/
│   │   ├── RuleEngine.java               ← from 9f
│   │   ├── FraudFeatureExtractor.java     ← from 9f
│   │   ├── DecisionTreeScorer.java        ← YOU CREATE THIS
│   │   └── FraudResult.java               ← YOU CREATE THIS
│   └── service/
│       └── FraudDetectionService.java     ← YOU CREATE THIS
└── test/java/com/payflow/routing/service/
    └── FraudDetectionServiceTest.java     ← YOU CREATE THIS
```

---

## 3. Step-by-Step: DecisionTreeScorer.java

**File:** `src/main/java/com/payflow/routing/fraud/DecisionTreeScorer.java`

An ML-inspired scorer that evaluates features through weighted "decision nodes."

### What Is a Decision Tree?

```
REAL ML DECISION TREE (what production systems use):
  Trained on millions of historical transactions
  Automatically learns: "if amount > 50K AND night AND prepaid → 95% fraud"
  Uses libraries: TensorFlow, scikit-learn, XGBoost

OUR SIMPLIFIED DECISION TREE (learning implementation):
  Same CONCEPT but with manually defined weights
  Demonstrates the architecture: feature extraction → weighted scoring → threshold
  Can be swapped for a real ML model later (same interface)
```

### Full Source Code

```java
package com.payflow.routing.fraud;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
```

```java
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
```

**7 FEATURE WEIGHTS — the "learned parameters" of our decision tree:**

| Weight Constant | Points | Feature It Applies To | Why This Weight |
|---|---|---|---|
| `WEIGHT_HIGH_AMOUNT` | 25 | `is_high_amount` (binary) | High-value fraud is the most costly |
| `WEIGHT_CARD_BIN_RISK` | 20 | `card_bin_risk` (0-1 continuous) | Prepaid/unknown cards are strong fraud signals |
| `WEIGHT_NIGHT_TRANSACTION` | 15 | `is_night_transaction` (binary) | Fraud peaks at night (victims asleep) |
| `WEIGHT_PAYMENT_METHOD_RISK` | 12 | `payment_method_risk` (0-1 continuous) | Prepaid instruments = higher fraud |
| `WEIGHT_INTERNATIONAL` | 10 | `is_international` (binary) | Cross-border fraud rate is ~2x domestic |
| `WEIGHT_AMOUNT_NORMALIZED` | 10 | `amount_normalized` (0-1 continuous) | Larger amounts = more attractive targets |
| `WEIGHT_ROUND_AMOUNT` | 8 | `is_round_amount` (binary) | Round amounts are slightly suspicious |

**TOTAL POSSIBLE SCORE:** If ALL features are maximally risky:
`25 + 15 + 10 + 8 + (1.0 × 20) + (1.0 × 12) + (1.0 × 10) = 100` — exactly 100.

### The score() Method — 7 Decision Nodes

```java
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
```

**BINARY FEATURE:** `is_high_amount` is 0.0 or 1.0. If 1.0 → add 25 points.

**`features.getOrDefault(key, 0.0)`** — if the feature isn't in the map, default to 0.0 (no contribution). Prevents `NullPointerException`.

```java
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
```

**NODES 1-4 are binary:** feature > 0 → add weight. Simple if/then decisions.

```java
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
```

**NODES 5-7 are continuous:** The score contribution is proportional to the feature value.

```
Card BIN risk examples:
  Visa (risk=0.2):     0.2 × 20.0 = 4.0 points
  Prepaid (risk=0.7):  0.7 × 20.0 = 14.0 points
  Unknown (risk=0.5):  0.5 × 20.0 = 10.0 points

Only adds a reason if risk > 0.5 (above moderate threshold)
```

```java
        // Clamp score to 0-100
        int finalScore = (int) Math.min(Math.max(totalScore, 0), 100);

        log.debug("Decision tree score: {} (raw: {:.2f}), reasons: {}", finalScore, totalScore, reasons.size());

        return new ScoringResult(finalScore, reasons);
    }
```

**CLAMPING:** `Math.min(Math.max(totalScore, 0), 100)` ensures the score is always in the 0-100 range.

```java
    /**
     * Scoring result with score and contributing factors.
     */
    public record ScoringResult(int score, List<String> reasons) {
    }
}
```

**NESTED RECORD** — same pattern as `RuleEngine.RuleResult`. Score + reasons, used by `FraudDetectionService`.

---

## 4. Step-by-Step: FraudResult.java

**File:** `src/main/java/com/payflow/routing/fraud/FraudResult.java`

The final output of the fraud detection pipeline — score, action, and reasons.

### Full Source Code

```java
package com.payflow.routing.fraud;

import java.util.List;

/**
 * Result of fraud analysis on a transaction.
 *
 * @param score   Fraud risk score (0-100, where 100 is highest risk)
 * @param action  Recommended action based on the score
 * @param reasons List of reasons contributing to the score
 */
public record FraudResult(
        int score,
        FraudAction action,
        List<String> reasons
) {
```

**A RECORD WITH 3 FIELDS:**

| Field | Type | Range | Example |
|---|---|---|---|
| `score` | int | 0-100 | 44 |
| `action` | FraudAction | APPROVE/REVIEW/DECLINE | REVIEW |
| `reasons` | List<String> | Human-readable explanations | ["High amount", "Night transaction"] |

```java
    /**
     * Fraud decision actions.
     */
    public enum FraudAction {
        /** Score < 30: Transaction is safe to process */
        APPROVE,
        /** Score 30-70: Transaction needs manual review */
        REVIEW,
        /** Score > 70: Transaction should be declined */
        DECLINE
    }
```

**THE THREE ZONES:**

```
Score:  0────────────30─────────────70────────────100
Action: |  APPROVE    |   REVIEW     |   DECLINE   |
        |  (safe)     | (flag+process)| (block!)    |
```

| Action | Score Range | What Happens |
|---|---|---|
| `APPROVE` | 0-29 | Route to bank normally |
| `REVIEW` | 30-70 | Route to bank BUT flag for human review |
| `DECLINE` | 71-100 | **Do NOT route to bank** — reject immediately |

```java
    /**
     * Determines the action based on the fraud score.
     *
     * @param score Fraud risk score (0-100)
     * @return Appropriate FraudAction
     */
    public static FraudAction actionFromScore(int score) {
        if (score < 30) return FraudAction.APPROVE;
        if (score <= 70) return FraudAction.REVIEW;
        return FraudAction.DECLINE;
    }

    /**
     * Factory method to create a FraudResult with auto-determined action.
     */
    public static FraudResult of(int score, List<String> reasons) {
        return new FraudResult(score, actionFromScore(score), reasons);
    }
```

**FACTORY METHOD `FraudResult.of()`** — creates a result with auto-determined action from score. The caller doesn't need to figure out which action to set.

```java
    /**
     * Returns true if the transaction should be blocked.
     */
    public boolean isDeclined() {
        return action == FraudAction.DECLINE;
    }

    /**
     * Returns true if the transaction requires review.
     */
    public boolean needsReview() {
        return action == FraudAction.REVIEW;
    }
}
```

**CONVENIENCE METHODS** — RoutingController uses `fraudResult.isDeclined()` to decide whether to proceed to routing.

---

## 5. Step-by-Step: FraudDetectionService.java

**File:** `src/main/java/com/payflow/routing/service/FraudDetectionService.java`

The **orchestrator** that combines the rule engine and ML scorer.

### Full Source Code

```java
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
```

```java
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
```

**3 DEPENDENCIES + 2 CONFIGURABLE WEIGHTS:**

| Dependency | Purpose |
|---|---|
| `RuleEngine` | Deterministic rules (velocity, amount, geo-blocking) |
| `DecisionTreeScorer` | ML-inspired weighted feature scoring |
| `FraudFeatureExtractor` | Transforms request into numeric features for the scorer |

| Weight | Default | From |
|---|---|---|
| `ruleWeight` | 0.6 (60%) | `fraud.weight.rules` in application.yml |
| `modelWeight` | 0.4 (40%) | `fraud.weight.model` in application.yml |

**WHY 60/40 AND NOT 50/50?**
```
Rules (60%):
  ✅ Fast and deterministic — same input always gives same output
  ✅ Zero false negatives for KNOWN patterns ("velocity > 5" always triggers)
  ✅ Explainable — "Declined because velocity exceeded 5 txns/min"
  ❌ Can't catch NOVEL patterns (rules need to be written by humans)

ML (40%):
  ✅ Catches novel patterns rules can't express
  ✅ Adapts to new fraud techniques (in production with real training)
  ❌ Less explainable ("the model said 0.85 risk")
  ❌ Can have false positives (legitimate patterns mistaken for fraud)

60/40 means rules have more influence because they're more reliable,
but ML still contributes enough to catch patterns rules miss.
```

```java
    public FraudDetectionService(RuleEngine ruleEngine,
                                 DecisionTreeScorer decisionTreeScorer,
                                 FraudFeatureExtractor featureExtractor) {
        this.ruleEngine = ruleEngine;
        this.decisionTreeScorer = decisionTreeScorer;
        this.featureExtractor = featureExtractor;
    }
```

**CONSTRUCTOR INJECTION** — no `@Autowired` needed (single constructor).

### analyze() — The 5-Step Pipeline

```java
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
```

**THE 5 STEPS visualized:**

```
Step 1: Rule Engine → score=40, violations=["High amount"]
Step 2: Feature Extract → {9 features} → ML Score → score=50, reasons=["Night txn"]
Step 3: Combined = (40 × 0.6) + (50 × 0.4) = 24 + 20 = 44
Step 4: Reasons = ["High amount", "Night txn"]
Step 5: FraudResult.of(44, reasons) → {score:44, action:REVIEW, reasons:[...]}
```

### isObviousFraud — Fast Path

```java
    /**
     * Quick check for obvious fraud indicators (bypasses ML scoring).
     * Used for fast-path rejection of clearly fraudulent transactions.
     */
    public boolean isObviousFraud(RoutingRequest request) {
        RuleEngine.RuleResult ruleResult = ruleEngine.evaluate(request);
        return ruleResult.score() > 70;
    }
}
```

**SHORT-CIRCUIT for obvious cases:** If rules alone score > 70, skip the ML scorer entirely. Saves the feature extraction + scoring computation for clearly fraudulent transactions (e.g., geo-blocked country = 80 points from rules alone).

---

## 6. Step-by-Step: FraudDetectionServiceTest.java

**File:** `src/test/java/com/payflow/routing/service/FraudDetectionServiceTest.java`

5 tests covering all three outcomes (APPROVE, REVIEW, DECLINE) plus reason aggregation.

### Setup

```java
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
```

**3 MOCKS** — all dependencies are mocked. We test the ORCHESTRATION logic, not the individual components.

**`ReflectionTestUtils.setField()`** — 🆕 **NEW TESTING PATTERN.** The `@Value` fields (`ruleWeight`, `modelWeight`) aren't injected in unit tests (no Spring context). `ReflectionTestUtils` sets them via reflection.

**WHY NOT `@SpringBootTest`?** That would start the full Spring context (slow). Unit tests only need the class under test + mocks.

### Test 1: Low Amount → APPROVE

```java
    @Test
    @DisplayName("analyze - low amount transaction should result in APPROVE")
    void analyze_LowAmount_ReturnsApprove() {
        RoutingRequest request = new RoutingRequest(
                "merchant-001", new BigDecimal("500"), "INR", "CARD", "411111", null);

        when(ruleEngine.evaluate(any(RoutingRequest.class)))
                .thenReturn(new RuleEngine.RuleResult(0, List.of()));

        when(featureExtractor.extractFeatures(any(RoutingRequest.class)))
                .thenReturn(Map.of());

        when(decisionTreeScorer.score(any(Map.class)))
                .thenReturn(new DecisionTreeScorer.ScoringResult(10, List.of()));

        FraudResult result = fraudDetectionService.analyze(request);

        assertThat(result.score()).isLessThan(30);
        assertThat(result.action()).isEqualTo(FraudResult.FraudAction.APPROVE);
    }
```

**MATH:** `(0 × 0.6) + (10 × 0.4) = 0 + 4 = 4` → score=4 → APPROVE

### Test 2: High Velocity → High Score

```java
    @Test
    @DisplayName("analyze - high velocity should produce high fraud score")
    void analyze_HighVelocity_ReturnsHighScore() {
        RoutingRequest request = new RoutingRequest(
                "merchant-001", new BigDecimal("2000"), "INR", "CARD", "411111", null);

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
```

**MATH:** `(50 × 0.6) + (40 × 0.4) = 30 + 16 = 46` → score=46 → REVIEW

### Test 3: Huge Amount → DECLINE

```java
    @Test
    @DisplayName("analyze - huge amount should result in DECLINE")
    void analyze_HugeAmount_ReturnsDecline() {
        RoutingRequest request = new RoutingRequest(
                "merchant-001", new BigDecimal("1000000"), "INR", "CARD", "411111", null);

        when(ruleEngine.evaluate(any(RoutingRequest.class)))
                .thenReturn(new RuleEngine.RuleResult(90, List.of("High amount: 1000000 exceeds threshold")));

        when(featureExtractor.extractFeatures(any(RoutingRequest.class)))
                .thenReturn(Map.of());

        when(decisionTreeScorer.score(any(Map.class)))
                .thenReturn(new DecisionTreeScorer.ScoringResult(80, List.of("High transaction amount detected")));

        FraudResult result = fraudDetectionService.analyze(request);

        // Combined: (90 * 0.6) + (80 * 0.4) = 54 + 32 = 86 → DECLINE
        assertThat(result.score()).isGreaterThan(70);
        assertThat(result.action()).isEqualTo(FraudResult.FraudAction.DECLINE);
    }
```

**MATH:** `(90 × 0.6) + (80 × 0.4) = 54 + 32 = 86` → score=86 → DECLINE

### Test 4: Medium Risk → REVIEW

```java
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
```

**`assertThat(result.score()).isBetween(30, 70)`** — AssertJ range assertion (inclusive on both ends).

### Test 5: Reason Aggregation

```java
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
```

**TESTS REASON AGGREGATION:** 1 rule violation + 2 model reasons = 3 total reasons in the FraudResult.

---

## 7. Scoring Examples — End to End

### Example 1: Normal Purchase (₹500, Visa, Afternoon)

```
Rule Engine:
  Velocity: 1 txn/min → score=0
  Amount: 500 < 50,000 → score=0
  Geo: INR → not blocked → score=0
  Total rule score: 0

Feature Extractor:
  amount=500, normalized=0.005, is_high=0, is_round=0
  hour=14, is_night=0, is_intl=1 (INR != USD)
  bin_risk=0.2 (Visa), method_risk=0.3 (credit)

Decision Tree:
  High amount: 0     → +0
  Night: 0            → +0
  International: 1    → +10
  Round: 0            → +0
  BIN risk: 0.2×20    → +4
  Method risk: 0.3×12 → +3.6
  Normalized: 0.005×10 → +0.05
  ML score: ~18

Final: (0 × 0.6) + (18 × 0.4) = 0 + 7.2 = 7 → APPROVE ✅
```

### Example 2: Suspicious (₹60,000, Night, Prepaid)

```
Rule Engine:
  Velocity: 1 txn/min → score=0
  Amount: 60,000 > 50,000, ratio=1.2 → score=15
  Geo: INR → not blocked → score=0
  Total rule score: 15

Decision Tree:
  High amount: 1      → +25
  Night: 1             → +15
  International: 1     → +10
  Round: 1 (60000)     → +8
  BIN risk: 0.6×20     → +12
  Method risk: 0.7×12  → +8.4
  Normalized: 0.6×10   → +6
  ML score: ~84 (capped at 100)

Final: (15 × 0.6) + (84 × 0.4) = 9 + 33.6 = 42 → REVIEW ⚠️
```

### Example 3: Fraud (₹10,00,000, North Korean Currency, 10 txns/min)

```
Rule Engine:
  Velocity: 10 txns/min, excess=5 → score=50 (capped)
  Amount: 1,000,000 > 50,000, ratio=20 → score=40
  Geo: KPW → North Korea → score=80
  Total rule score: 100 (capped)

Decision Tree:
  High amount: 1      → +25
  Night: depends       → +0 or +15
  International: 1     → +10
  Round: 1             → +8
  BIN risk: varies     → +4-14
  Method risk: varies  → +2-8
  Normalized: 1.0×10   → +10
  ML score: ~67+

Final: (100 × 0.6) + (67 × 0.4) = 60 + 26.8 = 86 → DECLINE ❌
```

---

## 8. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **Decision tree (simplified)** | Weighted feature evaluation — same architecture as real ML, manually tuned weights |
| 2 | **7 decision nodes** | 4 binary (if/then) + 3 continuous (proportional to feature value) |
| 3 | **Feature weights** | Higher weight = stronger signal. Total max = 100 |
| 4 | **`getOrDefault(key, 0.0)`** | Safe map access — returns default if key missing. Prevents NullPointerException |
| 5 | **FraudResult record** | 3 fields: score (0-100), action (enum), reasons (List<String>) |
| 6 | **FraudAction enum** | APPROVE (<30), REVIEW (30-70), DECLINE (>70) |
| 7 | **`FraudResult.of()` factory** | Auto-determines action from score — caller doesn't need to know thresholds |
| 8 | **`isDeclined()` / `needsReview()`** | Convenience methods for RoutingController to check action |
| 9 | **Weighted combination** | `(ruleScore × 0.6) + (mlScore × 0.4)` — configurable via @Value |
| 10 | **Why 60/40 split** | Rules = reliable for known patterns. ML = catches novel patterns. Rules get more weight |
| 11 | **Reason aggregation** | `allReasons.addAll(ruleResult.violations())` + `allReasons.addAll(modelResult.reasons())` |
| 12 | **`isObviousFraud()` fast path** | Skip ML scoring if rules alone score > 70 — saves computation |
| 13 | **`ReflectionTestUtils.setField()`** | Set @Value fields in unit tests without Spring context |
| 14 | **3 mocks per test** | RuleEngine, DecisionTreeScorer, FeatureExtractor — test orchestration logic only |
| 15 | **RoutingRequest constructor** | 6-arg constructor (merchantId, amount, currency, paymentMethod, cardBin, cardNumber) |
| 16 | **Score math in test comments** | Each test includes the expected calculation — makes tests self-documenting |
| 17 | **`isBetween(30, 70)`** | AssertJ range assertion — inclusive both ends |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part09-routing-service-overview.md) | Routing Service Overview |
| [Part 9a](./phase4-part09a-routing-project-setup.md) | Project Setup |
| [Part 9b](./phase4-part09b-iso8583-foundation.md) | ISO 8583 Foundation |
| [Part 9c](./phase4-part09c-iso8583-messages.md) | ISO 8583 Messages + Tests |
| [Part 9d](./phase4-part09d-netty-config-client.md) | Netty Config + Client |
| [Part 9e](./phase4-part09e-netty-pipeline.md) | Netty Pipeline |
| [Part 9f](./phase4-part09f-fraud-rule-engine.md) | Fraud Rule Engine |
| **Part 9g** | **Fraud ML + Service + Tests** (You are here) |
| [Part 9h](./phase4-part09h-smart-routing.md) | Smart Routing + Tests |
| [Part 9i](./phase4-part09i-config-classes.md) | Config Classes (DynamoDB, Resilience4j) |
| [Part 9j](./phase4-part09j-controller-docker.md) | Controller + DTOs + Docker + curl |
| [Part 9k](./phase4-part09k-connections-flows.md) | How Everything Connects |

---

*Next: [Part 9h — Smart Routing (Epsilon-Greedy Multi-Armed Bandit)](./phase4-part09h-smart-routing.md) →*
