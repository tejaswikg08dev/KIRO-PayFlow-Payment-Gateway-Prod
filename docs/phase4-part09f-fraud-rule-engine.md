# 🏗️ Phase 4 Part 9f: Routing Service — Fraud Rule Engine + Feature Extractor

> **"Three rules. Nine features. Before a single rupee leaves the system, the fraud engine scores every transaction — and blocks the suspicious ones before they reach the bank."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 9f — Fraud Rule Engine + Feature Extractor |
| **What You Build** | RuleEngine.java, FraudFeatureExtractor.java |
| **Previous** | [Part 9e — Netty Pipeline](./phase4-part09e-netty-pipeline.md) |
| **Next** | [Part 9g — Fraud ML Scorer + Service](./phase4-part09g-fraud-ml-service.md) |

---

## 📖 Table of Contents

1. [Why Fraud Detection Before Routing?](#1-why-fraud-detection-before-routing)
2. [The Two-Layer Architecture](#2-the-two-layer-architecture)
3. [Folder Structure After This Part](#3-folder-structure-after-this-part)
4. [Step-by-Step: RuleEngine.java](#4-step-by-step-ruleenginejava)
5. [Step-by-Step: FraudFeatureExtractor.java](#5-step-by-step-fraudfeatureextractorjava)
6. [How RuleEngine and FeatureExtractor Feed Into FraudDetectionService](#6-how-ruleengine-and-featureextractor-feed-into-frauddetectionservice)
7. [What You Learned](#7-what-you-learned)

---

## 1. Why Fraud Detection Before Routing?

```
WITHOUT fraud detection:
  Fraudster → ₹5,00,000 transaction → Route to bank → Bank approves → Money gone!
  Cost: ₹5,00,000 chargeback + ₹500 bank processing fee

WITH fraud detection:
  Fraudster → ₹5,00,000 transaction → Fraud score = 85 → DECLINE → Never reaches bank
  Cost: ₹0 (blocked before bank call, no processing fee)

Banks charge per authorization attempt (~₹0.50-₹2.00 per request).
Blocking fraud BEFORE the bank call:
  1. Saves the processing fee
  2. Saves the chargeback cost
  3. Keeps the merchant's success rate high
  4. Keeps PayFlow's reputation with banks
```

---

## 2. The Two-Layer Architecture

PayFlow uses a **hybrid** approach with two scoring layers:

```
┌───────────────────────────────────────────────────────────────┐
│                  FRAUD DETECTION PIPELINE                      │
├───────────────────────────────────────────────────────────────┤
│                                                               │
│  Transaction Request                                          │
│       │                                                       │
│       ├───────────────────────────┐                          │
│       │                           │                          │
│       ▼                           ▼                          │
│  ┌──────────────┐          ┌───────────────────┐            │
│  │ RULE ENGINE  │          │ FEATURE EXTRACTOR │            │
│  │ (this part)  │          │ (this part)       │            │
│  │              │          │     │              │            │
│  │ 3 rules:     │          │     ▼              │            │
│  │ • Velocity   │          │ ┌────────────────┐ │            │
│  │ • Amount     │          │ │ DECISION TREE  │ │            │
│  │ • Geo-block  │          │ │ SCORER (9g)    │ │            │
│  │              │          │ │ 7 weighted     │ │            │
│  │ Score: 0-100 │          │ │ nodes          │ │            │
│  │ Weight: 60%  │          │ │ Score: 0-100   │ │            │
│  └──────┬───────┘          │ │ Weight: 40%    │ │            │
│         │                  │ └────────┬───────┘ │            │
│         │                  └──────────┼─────────┘            │
│         │                             │                      │
│         ▼                             ▼                      │
│  ┌──────────────────────────────────────────┐               │
│  │ FraudDetectionService (Part 9g)          │               │
│  │ Final = (RuleScore × 0.6) + (ML × 0.4)  │               │
│  │                                          │               │
│  │ < 30  → APPROVE                          │               │
│  │ 30-70 → REVIEW                           │               │
│  │ > 70  → DECLINE                          │               │
│  └──────────────────────────────────────────┘               │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

**THIS PART (9f)** covers the LEFT side (RuleEngine) and the MIDDLE (FraudFeatureExtractor). Part 9g covers the Decision Tree Scorer and FraudDetectionService that combines them.

---

## 3. Folder Structure After This Part

```
backend/routing-service/src/main/java/com/payflow/routing/
├── RoutingServiceApplication.java    ← from 9a
├── config/NettyConfig.java           ← from 9d
├── iso8583/                          ← from 9b, 9c
├── netty/                            ← from 9d, 9e
└── fraud/                            ← YOU CREATE THIS FOLDER
    ├── RuleEngine.java               ← YOU CREATE THIS
    └── FraudFeatureExtractor.java    ← YOU CREATE THIS
```

---

## 4. Step-by-Step: RuleEngine.java

**File:** `src/main/java/com/payflow/routing/fraud/RuleEngine.java`

The rule engine runs **deterministic checks** — hard rules that ALWAYS trigger for specific patterns.

### Full Source Code

```java
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
```

```java
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
```

**THREE CONFIGURABLE THRESHOLDS** from `application.yml`:

| Property | Default | Meaning |
|---|---|---|
| `velocityThreshold` | 5 | More than 5 txns/minute from same merchant → suspicious |
| `amountThreshold` | 50,000 | Transactions above ₹50K trigger elevated scoring |
| `velocityWindowMs` | 60,000 | 60-second sliding window for velocity counting |

**WHY CONFIGURABLE?** Different merchants have different patterns:
- A grocery store: 50 txns/minute is normal (busy checkout)
- A luxury jewelry store: 5 txns/minute from the same card is suspicious
- In production, these thresholds would be per-merchant, not global

```java
    // In-memory velocity tracking (merchantId -> list of transaction timestamps)
    private final Map<String, List<Long>> velocityMap = new ConcurrentHashMap<>();
```

**IN-MEMORY VELOCITY TRACKING:**

| Type | Why This Type |
|---|---|
| `ConcurrentHashMap` | Multiple threads process transactions simultaneously — thread-safe map |
| `String` key | merchantId — track velocity per merchant |
| `List<Long>` value | Timestamps of recent transactions within the window |

**WHY NOT REDIS?** Redis was used for idempotency in Payment Service. The routing service keeps velocity data in-memory because:
1. Velocity data is ephemeral (60-second window)
2. In-memory is faster than Redis for per-request lookups
3. If the service restarts, velocity counters reset to 0 (acceptable — new service instance starts clean)

**IN PRODUCTION:** You'd use Redis or a distributed counter for velocity across multiple routing service instances.

```java
    // Geo-blocked countries
    private static final List<String> BLOCKED_COUNTRIES = List.of(
            "KP", // North Korea
            "IR", // Iran
            "SY", // Syria
            "CU"  // Cuba
    );
```

**SANCTIONS LIST:** These countries are blocked under international sanctions (OFAC, UN). Financial transactions to/from these countries are illegal for most payment processors.

**`List.of()` — immutable list** (Java 9+). Cannot be modified after creation. Perfect for a static reference list.

### The evaluate() Method — Run All 3 Rules

```java
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
```

**SCORING IS ADDITIVE:** Each rule contributes independently. A transaction can trigger MULTIPLE rules:
```
Velocity exceeded:        +30 points
High amount (₹2,00,000): +20 points
Total:                     50 points → REVIEW

If also geo-blocked:      +80 points
Total:                    100 points → DECLINE (capped at 100)
```

**`violations` list** — human-readable reasons for the score. Passed to FraudResult so the controller can log/return them.

### Rule 1: Velocity Check

```java
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
```

**SLIDING WINDOW PATTERN — step by step:**

```
Example: velocityThreshold=5, window=60 seconds

t=0s:  Txn 1 → timestamps=[0]        → size=1 ≤ 5 → score=0
t=10s: Txn 2 → timestamps=[0,10]     → size=2 ≤ 5 → score=0
t=20s: Txn 3 → timestamps=[0,10,20]  → size=3 ≤ 5 → score=0
t=30s: Txn 4 → timestamps=[0,...,30] → size=4 ≤ 5 → score=0
t=40s: Txn 5 → timestamps=[0,...,40] → size=5 ≤ 5 → score=0
t=45s: Txn 6 → timestamps=[0,...,45] → size=6 > 5 → excess=1 → score=15
t=50s: Txn 7 → timestamps=[0,...,50] → size=7 > 5 → excess=2 → score=30
t=65s: Txn 8 → remove expired (t=0)  → timestamps=[10,...,65] → size=7 > 5 → score=30
```

**KEY OPERATIONS:**

| Code | What It Does |
|---|---|
| `computeIfAbsent(merchantId, k -> new ArrayList<>())` | Create empty list for new merchants (thread-safe) |
| `synchronized (timestamps)` | Lock THIS merchant's list (other merchants aren't blocked) |
| `timestamps.removeIf(ts -> ts < windowStart)` | Evict timestamps older than 60 seconds |
| `timestamps.add(now)` | Record current transaction |
| `excess * 15` | Score increases: 1 over → 15, 2 over → 30, 3 over → 45 |
| `Math.min(excess * 15, 50)` | Cap velocity contribution at 50 (not all 100) |

**WHY `synchronized`?** ConcurrentHashMap provides thread-safe map operations, but the LIST inside is a regular ArrayList. Multiple threads modifying the same merchant's list need synchronization.

### Rule 2: Amount Threshold

```java
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
```

**TIERED SCORING — the further above threshold, the more suspicious:**

| Amount | Ratio (amount / 50K) | Score |
|---|---|---|
| ₹40,000 | 0.8 (below threshold) | 0 |
| ₹60,000 | 1.2 | 15 |
| ₹1,20,000 | 2.4 | 20 |
| ₹3,00,000 | 6.0 | 30 |
| ₹6,00,000 | 12.0 | 40 |

**WHY TIERED?** A ₹60,000 transaction might be a laptop purchase (legitimate). A ₹6,00,000 transaction is much more suspicious for card-not-present.

### Rule 3: Geo-Blocking

```java
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
```

**CURRENCY → COUNTRY MAPPING:**

| Currency Code | Country | Score |
|---|---|---|
| `"KPW"` | North Korea (KP) | 80 |
| `"IRR"` | Iran (IR) | 80 |
| `"SYP"` | Syria (SY) | 80 |
| `"CUP"` | Cuba (CU) | 80 |
| `"INR"` | India | 0 (not blocked) |
| `"USD"` | United States | 0 (not blocked) |

**SCORE = 80** — very high because geo-blocking is a near-certain indicator. Combined with the 0.6 rule weight: `80 × 0.6 = 48` contribution to final score. If any other rule also triggers, it crosses 70 → DECLINE.

**SIMPLIFIED IMPLEMENTATION:** In production, you'd use the customer's IP address or card-issuing country, not just currency. This is a learning implementation that demonstrates the concept.

### The RuleResult Record

```java
    /**
     * Result from rule engine evaluation.
     */
    public record RuleResult(int score, List<String> violations) {
        public boolean hasViolations() {
            return !violations.isEmpty();
        }
    }
}
```

**NESTED RECORD** — same pattern as `Iso8583MessageParser.Iso8583ParseException`. Keeps related types together.

**TWO FIELDS:**
- `score` (0-100) — numeric contribution to the final fraud score
- `violations` — human-readable list of which rules triggered

---

## 5. Step-by-Step: FraudFeatureExtractor.java

**File:** `src/main/java/com/payflow/routing/fraud/FraudFeatureExtractor.java`

This class extracts **numeric features** from a transaction for the ML-inspired scorer (Part 9g).

### What Are Features?

```
MACHINE LEARNING CONCEPT:
  A "feature" is a measurable property of the data.
  ML models don't understand "₹50,000 payment at 3 AM with a prepaid card from Nigeria."
  They understand: [amount=50000, hour=3, is_night=1, card_type_risk=0.7, is_international=1]
  
  Feature extraction TRANSLATES human-understandable data into numbers.
```

### Full Source Code

```java
package com.payflow.routing.fraud;

import com.payflow.routing.dto.RoutingRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
```

```java
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
```

**9 FEATURE CONSTANTS** — used as map keys. `public static final` so the `DecisionTreeScorer` (Part 9g) references them directly.

**WHY STRING CONSTANTS?** Instead of magic strings like `features.get("amount")`, use `features.get(FEATURE_AMOUNT)`. If you typo the constant name, the compiler catches it. If you typo a string, you get `null` at runtime.

### extractFeatures — The Main Method

```java
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
```

**ALL 9 FEATURES EXPLAINED:**

| Feature | Type | Range | How It's Calculated | Why It Matters for Fraud |
|---|---|---|---|---|
| `amount` | Continuous | 0+ | Raw amount in currency | Higher amounts = more attractive to fraudsters |
| `amount_normalized` | Continuous | 0.0-1.0 | `amount / 100,000` (capped at 1.0) | Normalizes for scoring (same scale as other features) |
| `is_high_amount` | Binary | 0.0 or 1.0 | `amount > 50,000` | Simple threshold flag |
| `is_round_amount` | Binary | 0.0 or 1.0 | Divisible by 100, no decimals | Fraudsters pick round numbers (₹50,000 not ₹49,723) |
| `is_night_transaction` | Binary | 0.0 or 1.0 | Hour 23-5 (11 PM - 5 AM) | Fraud peaks when victims are asleep |
| `hour_of_day` | Continuous | 0-23 | Current hour | Time pattern for continuous scoring |
| `is_international` | Binary | 0.0 or 1.0 | Currency is NOT USD | Cross-border transactions have higher fraud rates |
| `card_bin_risk` | Continuous | 0.0-1.0 | Based on card prefix | Prepaid/virtual cards have higher fraud |
| `payment_method_risk` | Continuous | 0.0-1.0 | Based on payment type | Prepaid instruments are riskier |

### Feature Calculation Helpers

```java
    /**
     * Normalizes amount to 0-1 range (based on max expected value of 100,000).
     */
    private double normalizeAmount(BigDecimal amount) {
        double maxAmount = 100000.0;
        return Math.min(amount.doubleValue() / maxAmount, 1.0);
    }
```

**WHY NORMALIZE?** ML models work better when all features are on the same scale (0-1). Without normalization, a raw amount of 50,000 would dominate a binary feature of 1.0.

```
₹500   → 500 / 100,000 = 0.005 (low)
₹50,000 → 50,000 / 100,000 = 0.5 (medium)
₹2,00,000 → 200,000 / 100,000 = 1.0 (capped at max)
```

```java
    /**
     * Checks if amount is a round number (potential fraud indicator).
     */
    private boolean isRoundAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().scale() <= 0
                && amount.remainder(BigDecimal.valueOf(100)).compareTo(BigDecimal.ZERO) == 0;
    }
```

**ROUND AMOUNT DETECTION:**
- `stripTrailingZeros().scale() <= 0` → no decimal digits (₹50000, not ₹49999.50)
- `remainder(100) == 0` → divisible by 100

```
₹50,000.00 → scale=0 after strip, remainder(100)=0 → ROUND ✓
₹49,723.50 → scale=1 after strip → NOT round ✗
₹100.00    → scale=0, remainder(100)=0 → ROUND ✓
₹99.00     → scale=0, remainder(100)=99 → NOT round ✗
```

**WHY IS ROUND SUSPICIOUS?** Fraudsters testing stolen cards often use round amounts (₹1,000, ₹10,000, ₹50,000). Legitimate purchases tend to have odd amounts (₹4,299, ₹12,735).

```java
    /**
     * Checks if the transaction is international (non-domestic currency).
     */
    private boolean isInternational(String currency) {
        // Consider USD as domestic; everything else is international
        return currency != null && !"USD".equalsIgnoreCase(currency);
    }
```

**SIMPLIFIED:** Treats USD as domestic. In a real system, the domestic currency would be configurable (INR for Indian merchants).

```java
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
```

**CARD BIN = BANK IDENTIFICATION NUMBER** — the first 6 digits of a card number identify the issuing bank and card type:

| BIN Prefix | Card Network | Risk Level | Score |
|---|---|---|---|
| `4xxxxx` | Visa | Low (established) | 0.2 |
| `5xxxxx` | Mastercard | Low (established) | 0.2 |
| `6xxxxx` | Discover/Prepaid | Higher (prepaid cards used for fraud) | 0.6 |
| null/empty | Unknown | Moderate (can't assess) | 0.5 |
| Other | Other | Moderate | 0.4 |

**IN PRODUCTION:** You'd use a BIN database (like Binlist or MaxMind) with millions of entries mapping BINs to card types, issuing banks, and countries.

```java
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
```

**PAYMENT METHOD RISK SCORING:**

| Method | Risk | Score | Why |
|---|---|---|---|
| DEBIT_CARD | Lowest | 0.2 | Linked to bank account — easier to trace |
| CREDIT_CARD | Low | 0.3 | Has chargeback protection |
| WALLET | Moderate | 0.4 | Semi-anonymous but has KYC |
| PREPAID | Highest | 0.7 | Often anonymous, bought with cash, used for fraud |
| null/unknown | Moderate | 0.5 | Can't assess risk without data |

---

## 6. How RuleEngine and FeatureExtractor Feed Into FraudDetectionService

```
RoutingRequest (from PaymentService via Feign)
  │
  ├──────────────────────────┐
  │                          │
  ▼                          ▼
RuleEngine.evaluate()    FraudFeatureExtractor.extractFeatures()
  │                          │
  │ RuleResult:              │ Map<String, Double>:
  │  score: 30               │  {amount: 60000.0,
  │  violations: [           │   amount_normalized: 0.6,
  │   "High amount: 60000"]  │   is_high_amount: 1.0,
  │                          │   is_round_amount: 0.0,
  │                          │   is_night_txn: 0.0,
  │                          │   hour_of_day: 14.0,
  │                          │   is_international: 1.0,
  │                          │   card_bin_risk: 0.2,
  │                          │   payment_method_risk: 0.3}
  │                          │
  ▼                          ▼
FraudDetectionService.analyze() ← combines both (Part 9g)
  │
  │ finalScore = (30 × 0.6) + (mlScore × 0.4)
  │            = 18 + (mlScore × 0.4)
  │ if mlScore = 25: final = 18 + 10 = 28 → APPROVE
  │ if mlScore = 55: final = 18 + 22 = 40 → REVIEW
```

---

## 7. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **Why fraud before bank** | Save processing fees, prevent chargebacks, block before money moves |
| 2 | **Rule-based scoring** | Deterministic — same input always gives same output. Fast. Explainable. |
| 3 | **Additive scoring** | Each rule contributes independently. Multiple triggers compound. |
| 4 | **Velocity check** | Count transactions per merchant in a sliding time window. `ConcurrentHashMap` + `synchronized` |
| 5 | **Sliding window pattern** | `removeIf(ts < windowStart)` evicts old entries on every check |
| 6 | **Tiered amount scoring** | Higher amounts → higher scores. Ratio-based tiers (2x, 5x, 10x threshold) |
| 7 | **Geo-blocking** | Currency → country mapping. Sanctioned countries → 80 points (near-automatic decline) |
| 8 | **RuleResult record** | Nested record with score + violations list. `hasViolations()` convenience method |
| 9 | **Feature extraction** | Transform human data into numeric features (Map<String, Double>) for ML scoring |
| 10 | **9 features extracted** | Amount (raw, normalized, high, round), time (hour, night), international, BIN risk, method risk |
| 11 | **Normalization** | Scale amount to 0-1 range so it doesn't dominate binary features |
| 12 | **Round amount detection** | `stripTrailingZeros().scale() <= 0 && remainder(100) == 0` — fraudsters pick round numbers |
| 13 | **BIN risk scoring** | Card prefix → risk level. Visa/MC = 0.2 (low), Prepaid = 0.7 (high) |
| 14 | **Payment method risk** | Debit = 0.2 (low), Prepaid = 0.7 (high) — anonymous methods are riskier |
| 15 | **`List.of()` immutable** | Java 9+ immutable list for static reference data (blocked countries) |
| 16 | **String constants for map keys** | `FEATURE_AMOUNT` instead of `"amount"` — compiler catches typos |
| 17 | **`ConcurrentHashMap` + `synchronized(list)`** | Thread-safe map, but individual lists still need synchronization |

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
| **Part 9f** | **Fraud Rule Engine + Feature Extractor** (You are here) |
| [Part 9g](./phase4-part09g-fraud-ml-service.md) | Fraud ML + Service + Tests |
| [Part 9h](./phase4-part09h-smart-routing.md) | Smart Routing + Tests |
| [Part 9i](./phase4-part09i-config-classes.md) | Config Classes (DynamoDB, Resilience4j) |
| [Part 9j](./phase4-part09j-controller-docker.md) | Controller + DTOs + Docker + curl |
| [Part 9k](./phase4-part09k-connections-flows.md) | How Everything Connects |

---

*Next: [Part 9g — Fraud ML Scorer + FraudDetectionService + Tests](./phase4-part09g-fraud-ml-service.md) →*
