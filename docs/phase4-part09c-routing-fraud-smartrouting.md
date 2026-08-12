# Phase 4 · Part 9C — Fraud Detection & Smart Routing

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Routing & Bank Integration |
| **Part** | 9C — Fraud Detection + Smart Routing |
| **Previous** | [Part 9B — Netty TCP Client](./phase4-part09b-routing-netty.md) |
| **Next** | [Part 10 — Bank Simulator](./phase4-part10-bank-simulator.md) |
| **Time** | ~3.5 hours |
| **Difficulty** | ★★★★☆ (Advanced) |
| **Prerequisites** | Part 9A (ISO 8583), Part 9B (Netty), Resilience4j basics |
| **What You'll Build** | Fraud scoring engine + intelligent bank routing with circuit breakers |
| **Git Commit** | `feat(routing): add fraud detection and smart routing with circuit breaker` |

---

## Table of Contents

1. [FraudDetectionService Overview](#1-frauddetectionservice-overview)
2. [RuleEngine — Velocity, Threshold, Geo-blocking](#2-ruleengine)
3. [DecisionTreeScorer — ML-Inspired Scoring](#3-decisiontreescorer)
4. [FraudResult — Decision Categories](#4-fraudresult)
5. [SmartRoutingService — Multi-Armed Bandit](#5-smartroutingservice)
6. [Explore vs Exploit](#6-explore-vs-exploit)
7. [BankRoute Model](#7-bankroute-model)
8. [RoutingController — Orchestration](#8-routingcontroller)
9. [Circuit Breaker with Resilience4j](#9-circuit-breaker)
10. [Circuit Breaker States Diagram](#10-circuit-breaker-states-diagram)
11. [What You Learned](#11-what-you-learned)
12. [Common Errors & Fixes](#12-common-errors--fixes)
13. [Git Commit](#13-git-commit)

---

## What You'll Learn

- How to combine rule-based and ML-inspired fraud scoring
- Why a weighted hybrid (60% rules + 40% ML) catches more fraud than either alone
- How the multi-armed bandit algorithm optimizes bank selection
- Why epsilon-greedy explores new banks while exploiting known-good ones
- How Resilience4j circuit breaker prevents cascading failures
- How to orchestrate fraud check → routing → bank call in one controller

---

## 1. FraudDetectionService Overview

The fraud detection system uses a **hybrid scoring approach**: combining deterministic rules (fast, explainable) with statistical scoring (catches patterns rules miss).

```
┌──────────────────────────────────────────────────────────────────┐
│                    FRAUD DETECTION PIPELINE                       │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Payment Request                                                  │
│       │                                                           │
│       ▼                                                           │
│  ┌─────────────────┐                                             │
│  │  Rule Engine     │ ──→ Score: 0-100 (weight: 60%)             │
│  │  • Velocity      │                                             │
│  │  • Amount        │                                             │
│  │  • Geo-blocking  │                                             │
│  └─────────────────┘                                             │
│       │                                                           │
│       ▼                                                           │
│  ┌─────────────────┐                                             │
│  │  ML Scorer       │ ──→ Score: 0-100 (weight: 40%)             │
│  │  • Features      │                                             │
│  │  • Decision Tree │                                             │
│  │  • Weighted sum  │                                             │
│  └─────────────────┘                                             │
│       │                                                           │
│       ▼                                                           │
│  Final Score = (RuleScore × 0.6) + (MLScore × 0.4)              │
│       │                                                           │
│       ▼                                                           │
│  ┌─────────────────────────────────────────────────────┐        │
│  │  Score < 30  → APPROVE   (proceed to routing)       │        │
│  │  Score 30-70 → REVIEW    (flag for manual review)   │        │
│  │  Score > 70  → DECLINE   (reject immediately)       │        │
│  └─────────────────────────────────────────────────────┘        │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

```java
package com.payflow.routing.fraud;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class FraudDetectionService {

    private final RuleEngine ruleEngine;
    private final DecisionTreeScorer mlScorer;

    // WHY 60/40 split:
    // Rules catch known patterns (fast, zero false negatives for known fraud)
    // ML catches novel patterns (adaptive, finds fraud rules can't express)
    // Together they give better precision AND recall than either alone
    private static final double RULE_WEIGHT = 0.6;
    private static final double ML_WEIGHT = 0.4;

    public FraudResult evaluate(FraudContext context) {
        // Step 1: Run rule engine (deterministic checks)
        int ruleScore = ruleEngine.evaluate(context);
        log.debug("Rule engine score: {} for txn {}", ruleScore, context.getTransactionId());

        // Step 2: Run ML scorer (statistical analysis)
        int mlScore = mlScorer.score(context);
        log.debug("ML scorer result: {} for txn {}", mlScore, context.getTransactionId());

        // Step 3: Combine scores with weights
        double finalScore = (ruleScore * RULE_WEIGHT) + (mlScore * ML_WEIGHT);

        // Step 4: Make decision based on thresholds
        FraudDecision decision = categorize(finalScore);

        log.info("Fraud evaluation: txn={}, ruleScore={}, mlScore={}, final={:.1f}, decision={}",
                context.getTransactionId(), ruleScore, mlScore, finalScore, decision);

        return FraudResult.builder()
                .transactionId(context.getTransactionId())
                .ruleScore(ruleScore)
                .mlScore(mlScore)
                .finalScore(finalScore)
                .decision(decision)
                .build();
    }

    private FraudDecision categorize(double score) {
        // WHY these thresholds:
        // < 30: Very low risk — 95%+ of legitimate transactions fall here
        // 30-70: Uncertain — needs human review (merchant's fraud team)
        // > 70: High confidence fraud — auto-decline to protect merchant
        if (score < 30) return FraudDecision.APPROVE;
        if (score <= 70) return FraudDecision.REVIEW;
        return FraudDecision.DECLINE;
    }
}
```

---

## 2. RuleEngine

Each rule contributes points to the score. More points = more suspicious.

```java
package com.payflow.routing.fraud;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class RuleEngine {

    private final RedisTemplate<String, String> redisTemplate;

    // WHY List<FraudRule>: Easy to add/remove rules without modifying this class
    // Each rule is independent and returns a score contribution
    private final List<FraudRule> rules = List.of(
            new VelocityRule(),
            new AmountThresholdRule(),
            new GeoBlockingRule()
    );

    public int evaluate(FraudContext context) {
        // WHY sum: Each rule contributes independently. A transaction might
        // trigger multiple rules (high amount + new country = very suspicious)
        return rules.stream()
                .mapToInt(rule -> rule.apply(context, redisTemplate))
                .sum();
    }
}
```

### Rule 1: Velocity Check

```java
package com.payflow.routing.fraud.rules;

import com.payflow.routing.fraud.FraudContext;
import com.payflow.routing.fraud.FraudRule;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Velocity Rule: How many transactions has this card done recently?
 *
 * WHY: Stolen cards are used rapidly before the owner notices.
 * Normal behavior: 2-3 transactions per hour
 * Suspicious: 5+ transactions in 10 minutes
 * Definite fraud: 10+ transactions in 5 minutes
 */
public class VelocityRule implements FraudRule {

    @Override
    public int apply(FraudContext context, RedisTemplate<String, String> redis) {
        String key = "fraud:velocity:" + context.getCardHash();

        // WHY increment + expire: Count transactions in a sliding 10-minute window
        // Redis INCR is atomic — safe for concurrent transactions on same card
        Long count = redis.opsForValue().increment(key);
        if (count == 1) {
            // WHY: Set expiry only on first increment (sliding window)
            redis.expire(key, Duration.ofMinutes(10));
        }

        // WHY these thresholds: Based on industry data
        // Normal shoppers rarely do more than 3 txns in 10 minutes
        if (count > 10) return 50;  // Almost certainly fraud
        if (count > 5) return 30;   // Very suspicious
        if (count > 3) return 15;   // Slightly suspicious
        return 0;                    // Normal behavior
    }

    @Override
    public String name() {
        return "VELOCITY_CHECK";
    }
}
```

### Rule 2: Amount Threshold

```java
package com.payflow.routing.fraud.rules;

/**
 * Amount Threshold Rule: Is this transaction unusually large?
 *
 * WHY: Fraudsters try to extract maximum value quickly.
 * Combined with velocity, this catches "drain the card" attacks.
 */
public class AmountThresholdRule implements FraudRule {

    @Override
    public int apply(FraudContext context, RedisTemplate<String, String> redis) {
        long amountPaise = context.getAmountInPaise();

        // WHY: Thresholds in paise (Indian smallest unit)
        // ₹1,00,000+ = very high (unusual for card-not-present)
        // ₹50,000-1,00,000 = high but possible (electronics, jewelry)
        // < ₹50,000 = normal consumer purchase

        if (amountPaise > 10_000_00) return 35;     // > ₹1,00,000
        if (amountPaise > 5_000_00) return 15;      // > ₹50,000
        if (amountPaise > 1_000_00) return 5;       // > ₹10,000
        return 0;
    }

    @Override
    public String name() {
        return "AMOUNT_THRESHOLD";
    }
}
```

### Rule 3: Geo-blocking

```java
package com.payflow.routing.fraud.rules;

import java.util.Set;

/**
 * Geo-blocking Rule: Is the transaction from a high-risk country?
 *
 * WHY: Certain countries have disproportionately high fraud rates.
 * This is NOT discrimination — it's based on fraud data from card networks.
 * The merchant can configure their own blocked countries.
 */
public class GeoBlockingRule implements FraudRule {

    // WHY Set: O(1) lookup instead of O(n) list scan
    private static final Set<String> HIGH_RISK_COUNTRIES = Set.of(
            "NG", "GH", "CM", "CI"  // Example high-risk country codes
    );

    private static final Set<String> MEDIUM_RISK_COUNTRIES = Set.of(
            "RO", "BG", "UA", "BY"  // Example medium-risk country codes
    );

    @Override
    public int apply(FraudContext context, RedisTemplate<String, String> redis) {
        String country = context.getIpCountryCode();

        if (country == null) return 10;  // WHY: Unknown country is suspicious itself

        if (HIGH_RISK_COUNTRIES.contains(country)) return 40;
        if (MEDIUM_RISK_COUNTRIES.contains(country)) return 20;

        // WHY: Check if transaction country differs from card's issuing country
        // A card issued in India being used from Romania = suspicious
        if (!country.equals(context.getCardIssuingCountry())) return 15;

        return 0;
    }

    @Override
    public String name() {
        return "GEO_BLOCKING";
    }
}
```

---

## 3. DecisionTreeScorer

A simplified ML-inspired scorer that extracts features and weights them.

```java
package com.payflow.routing.fraud;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

/**
 * Decision Tree Scorer — ML-inspired fraud detection
 *
 * WHY not real ML: A production system would use TensorFlow/PyTorch served via API.
 * This demonstrates the CONCEPT: feature extraction → weighted scoring → threshold.
 * The architecture allows swapping this with a real ML model later.
 */
@Slf4j
@Component
public class DecisionTreeScorer {

    public int score(FraudContext context) {
        // Step 1: Extract features (same as ML feature engineering)
        double[] features = extractFeatures(context);

        // Step 2: Apply weights (in real ML, these are learned from training data)
        double[] weights = {0.25, 0.20, 0.15, 0.15, 0.10, 0.10, 0.05};

        // Step 3: Weighted sum → normalize to 0-100
        double rawScore = 0;
        for (int i = 0; i < features.length; i++) {
            rawScore += features[i] * weights[i];
        }

        // WHY clamp to 0-100: Ensures score is always in valid range
        return (int) Math.max(0, Math.min(100, rawScore));
    }

    private double[] extractFeatures(FraudContext context) {
        return new double[]{
            // Feature 1: Transaction amount normalized (0-100)
            // WHY normalize: Different features need same scale for fair weighting
            normalizeAmount(context.getAmountInPaise()),

            // Feature 2: Time-of-day risk (fraud peaks at 2-5 AM)
            timeOfDayRisk(),

            // Feature 3: Card age risk (new cards used fraudulently more often)
            cardAgeRisk(context.getCardCreatedDaysAgo()),

            // Feature 4: Merchant category risk (digital goods = higher fraud)
            merchantCategoryRisk(context.getMerchantCategory()),

            // Feature 5: Device fingerprint mismatch
            deviceRisk(context.isNewDevice()),

            // Feature 6: Email domain risk (disposable emails = suspicious)
            emailDomainRisk(context.getCustomerEmail()),

            // Feature 7: BIN (Bank Identification Number) risk
            binRisk(context.getCardBin())
        };
    }

    private double normalizeAmount(long amountPaise) {
        // WHY log scale: ₹100 and ₹1000 are both "normal"
        // but ₹100,000 vs ₹1,000,000 — the difference matters less
        double logAmount = Math.log10(amountPaise + 1);
        return Math.min(100, logAmount * 15);
    }

    private double timeOfDayRisk() {
        int hour = LocalTime.now().getHour();
        // WHY: Legitimate transactions cluster around business hours
        // Fraudsters operate at 2-5 AM when victims are asleep
        if (hour >= 2 && hour <= 5) return 60;
        if (hour >= 0 && hour <= 6) return 30;
        return 5;
    }

    private double cardAgeRisk(int daysOld) {
        // WHY: Cards stolen at creation (data breach) are used immediately
        if (daysOld < 7) return 70;
        if (daysOld < 30) return 40;
        if (daysOld < 90) return 20;
        return 5;
    }

    private double merchantCategoryRisk(String mcc) {
        // WHY: Digital goods (gift cards, crypto) are preferred by fraudsters
        // because they're irreversible and instant
        return switch (mcc) {
            case "6051" -> 60;  // Cryptocurrency
            case "5816" -> 50;  // Digital games
            case "4829" -> 45;  // Money transfer
            default -> 10;
        };
    }

    private double deviceRisk(boolean isNewDevice) {
        // WHY: Fraudster uses different device than legitimate cardholder
        return isNewDevice ? 50 : 5;
    }

    private double emailDomainRisk(String email) {
        if (email == null) return 30;
        // WHY: Disposable email services used to avoid identification
        if (email.endsWith("@tempmail.com") || email.endsWith("@guerrillamail.com")) return 60;
        if (email.endsWith("@gmail.com") || email.endsWith("@outlook.com")) return 5;
        return 15;
    }

    private double binRisk(String bin) {
        // WHY: Certain BIN ranges have higher fraud rates (prepaid cards, virtual cards)
        if (bin == null) return 20;
        if (bin.startsWith("4000")) return 40;  // Known test/virtual card range
        return 5;
    }
}
```

---

## 4. FraudResult

```java
package com.payflow.routing.fraud;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FraudResult {
    private String transactionId;
    private int ruleScore;       // 0-100 from RuleEngine
    private int mlScore;         // 0-100 from DecisionTreeScorer
    private double finalScore;   // Weighted combination
    private FraudDecision decision;
}

public enum FraudDecision {
    APPROVE,   // Score < 30  — proceed with payment
    REVIEW,    // Score 30-70 — flag for human review, still process
    DECLINE    // Score > 70  — reject the transaction immediately
}
```

### Decision Matrix

| Final Score | Decision | Action | Example Scenario |
|-------------|----------|--------|-----------------|
| 0-29 | APPROVE | Route to bank | Regular ₹500 purchase, known device |
| 30-49 | REVIEW | Route to bank + flag | ₹50,000 purchase, new device |
| 50-70 | REVIEW | Route to bank + flag + alert merchant | ₹80,000, velocity=5/10min |
| 71-100 | DECLINE | Reject immediately | ₹2,00,000, velocity=10/5min, blocked country |

---

## 5. SmartRoutingService

Uses a **multi-armed bandit** algorithm to choose the best bank for each transaction.

```java
package com.payflow.routing.smartroute;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Smart Routing Service — Multi-Armed Bandit (Epsilon-Greedy)
 *
 * WHY multi-armed bandit:
 * Imagine a casino with multiple slot machines (bandits). Each has an unknown payout rate.
 * You want to maximize your winnings. Do you:
 *   A) Always play the machine that's paid best so far? (exploit)
 *   B) Sometimes try other machines to discover if they're actually better? (explore)
 *
 * Answer: BOTH! That's epsilon-greedy.
 *
 * For us:
 * - "Machines" = bank routes (HDFC, SBI, ICICI, etc.)
 * - "Payout" = successful transaction (approved by bank)
 * - "Explore" = try a random bank (10% of the time)
 * - "Exploit" = use the best-performing bank (90% of the time)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmartRoutingService {

    // WHY ConcurrentHashMap: Multiple threads route transactions simultaneously
    private final ConcurrentHashMap<String, BankRoute> bankRoutes = new ConcurrentHashMap<>();

    // WHY 0.10: 10% exploration gives enough data to detect changes in bank performance
    // while still routing 90% to the best bank (maximizing success rate)
    private static final double EPSILON = 0.10;

    /**
     * Select the best bank for this transaction.
     *
     * @param cardBin       First 6 digits of card (determines which banks can process it)
     * @param amount        Transaction amount (some banks have limits)
     * @param currency      Currency code (INR, USD, etc.)
     * @return Selected bank route
     */
    public BankRoute selectRoute(String cardBin, long amount, String currency) {
        // Step 1: Get eligible banks for this card/amount/currency
        List<BankRoute> eligible = getEligibleBanks(cardBin, amount, currency);

        if (eligible.isEmpty()) {
            throw new NoEligibleBankException("No bank can process: bin=" + cardBin
                    + ", amount=" + amount + ", currency=" + currency);
        }

        // Step 2: Epsilon-greedy selection
        BankRoute selected;
        if (ThreadLocalRandom.current().nextDouble() < EPSILON) {
            // EXPLORE: Pick a random bank (10% of the time)
            selected = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
            log.debug("🎲 EXPLORE: Randomly selected bank={}", selected.getBankCode());
        } else {
            // EXPLOIT: Pick the bank with highest composite score (90% of the time)
            selected = eligible.stream()
                    .max(Comparator.comparingDouble(BankRoute::getCompositeScore))
                    .orElseThrow();
            log.debug("🎯 EXPLOIT: Best bank={} (score={:.3f})",
                    selected.getBankCode(), selected.getCompositeScore());
        }

        return selected;
    }

    /**
     * Update bank performance after a transaction completes.
     * WHY: This is how the algorithm LEARNS — each result refines the bank's score.
     */
    public void recordResult(String bankCode, boolean success, long latencyMs) {
        bankRoutes.compute(bankCode, (key, route) -> {
            if (route == null) return null;

            // WHY: Exponential moving average (recent results matter more)
            // Alpha=0.1 means: new_avg = 0.9 * old_avg + 0.1 * new_value
            double alpha = 0.1;
            double successValue = success ? 1.0 : 0.0;

            route.setSuccessRate(
                    route.getSuccessRate() * (1 - alpha) + successValue * alpha);
            route.setAvgLatencyMs(
                    (long) (route.getAvgLatencyMs() * (1 - alpha) + latencyMs * alpha));
            route.incrementTotalTransactions();

            return route;
        });
    }

    private List<BankRoute> getEligibleBanks(String cardBin, long amount, String currency) {
        // WHY filter: Not all banks can process all cards
        // HDFC might not process SBI cards, Visa goes through specific acquirers
        return bankRoutes.values().stream()
                .filter(bank -> bank.supportsCardBin(cardBin))
                .filter(bank -> bank.supportsAmount(amount))
                .filter(bank -> bank.supportsCurrency(currency))
                .filter(BankRoute::isHealthy)  // Skip banks with open circuit breaker
                .toList();
    }
}
```

---

## 6. Explore vs Exploit

### Why Both Are Needed

```
Scenario: HDFC has 95% success rate, ICICI has 92% (but ICICI recently upgraded)

PURE EXPLOIT (always pick best):
  Transaction 1 → HDFC (95%) ✅
  Transaction 2 → HDFC (95%) ✅
  Transaction 3 → HDFC (95%) ✅
  ...
  Transaction 1000 → HDFC (95%) ✅
  Problem: We NEVER discover that ICICI is now at 98%!

PURE EXPLORE (always random):
  Transaction 1 → ICICI (98%) ✅
  Transaction 2 → SBI (88%) ❌
  Transaction 3 → HDFC (95%) ✅
  Transaction 4 → SBI (88%) ❌
  Problem: We send 33% of traffic to SBI (worst bank)!

EPSILON-GREEDY (10% explore, 90% exploit):
  Transaction 1 → HDFC (exploit) ✅
  Transaction 2 → HDFC (exploit) ✅
  ...
  Transaction 8 → HDFC (exploit) ✅
  Transaction 9 → ICICI (explore!) ✅  ← discovers ICICI improved!
  Transaction 10 → HDFC (exploit) ✅
  ...
  After 100 transactions: ICICI's score updated to 98%
  Now ICICI becomes the exploit choice!
  
  Result: Best performance with continuous adaptation 🎉
```

### Composite Score Calculation

```java
// Inside BankRoute.java
public double getCompositeScore() {
    // WHY weighted formula:
    // - Success rate (70%): Most important — failed transactions lose money
    // - Latency (20%): Fast banks give better UX (but not at cost of reliability)
    // - Cost (10%): Lower MDR means more profit for merchant

    double latencyScore = 1.0 - (Math.min(avgLatencyMs, 5000) / 5000.0);
    double costScore = 1.0 - (costPercentage / 5.0);  // Normalize: 5% = worst

    return (successRate * 0.70) + (latencyScore * 0.20) + (costScore * 0.10);
}
```

---

## 7. BankRoute Model

```java
package com.payflow.routing.smartroute;

import lombok.Data;
import java.util.Set;

@Data
public class BankRoute {
    private String bankCode;           // "HDFC", "SBI", "ICICI"
    private String host;               // "hdfc-bank.example.com"
    private int port;                  // 9090
    private double successRate;        // 0.0 to 1.0 (e.g., 0.95 = 95%)
    private long avgLatencyMs;         // Average response time in milliseconds
    private double costPercentage;     // MDR cost (e.g., 2.0 = 2%)
    private long totalTransactions;    // Total processed (for confidence)
    private boolean healthy;           // Circuit breaker status

    // WHY Sets: O(1) lookup for eligibility checks
    private Set<String> supportedBins;      // Card BIN prefixes this bank handles
    private Set<String> supportedCurrencies; // Currencies this bank processes
    private long maxAmountPaise;            // Maximum transaction amount

    public boolean supportsCardBin(String bin) {
        return supportedBins.stream().anyMatch(bin::startsWith);
    }

    public boolean supportsAmount(long amount) {
        return amount <= maxAmountPaise;
    }

    public boolean supportsCurrency(String currency) {
        return supportedCurrencies.contains(currency);
    }

    public void incrementTotalTransactions() {
        totalTransactions++;
    }

    public double getCompositeScore() {
        double latencyScore = 1.0 - (Math.min(avgLatencyMs, 5000) / 5000.0);
        double costScore = 1.0 - (costPercentage / 5.0);
        return (successRate * 0.70) + (latencyScore * 0.20) + (costScore * 0.10);
    }
}
```

| Bank | Success Rate | Avg Latency | Cost | Composite Score |
|------|-------------|-------------|------|----------------|
| HDFC | 0.95 | 800ms | 2.0% | 0.95×0.7 + 0.84×0.2 + 0.6×0.1 = **0.893** |
| SBI | 0.88 | 1200ms | 1.5% | 0.88×0.7 + 0.76×0.2 + 0.7×0.1 = **0.838** |
| ICICI | 0.92 | 600ms | 2.5% | 0.92×0.7 + 0.88×0.2 + 0.5×0.1 = **0.870** |

HDFC wins → gets 90% of traffic. SBI/ICICI get 10% (exploration).

---

## 8. RoutingController

Orchestrates the full flow: fraud check → smart route → bank call.

```java
package com.payflow.routing.controller;

import com.payflow.routing.fraud.*;
import com.payflow.routing.netty.BankNettyClient;
import com.payflow.routing.smartroute.*;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/internal/route")
@RequiredArgsConstructor
@Slf4j
public class RoutingController {

    private final FraudDetectionService fraudService;
    private final SmartRoutingService routingService;
    private final BankNettyClient bankClient;

    /**
     * POST /internal/route — Called by payment-service to authorize a transaction
     *
     * WHY "internal": This endpoint is NOT exposed to merchants.
     * It's called service-to-service (payment-service → routing-service).
     */
    @PostMapping
    public ResponseEntity<RoutingResponse> routeTransaction(@RequestBody RoutingRequest request) {

        // ═══════════ STEP 1: FRAUD CHECK ═══════════
        FraudContext fraudContext = buildFraudContext(request);
        FraudResult fraudResult = fraudService.evaluate(fraudContext);

        if (fraudResult.getDecision() == FraudDecision.DECLINE) {
            // WHY: Don't even attempt to route — save bank call costs
            log.warn("🚫 Transaction declined by fraud engine: txn={}, score={:.1f}",
                    request.getTransactionId(), fraudResult.getFinalScore());
            return ResponseEntity.ok(RoutingResponse.declined(fraudResult));
        }

        // ═══════════ STEP 2: SMART ROUTING ═══════════
        BankRoute selectedBank = routingService.selectRoute(
                request.getCardBin(),
                request.getAmountPaise(),
                request.getCurrency()
        );
        log.info("📍 Selected bank: {} for txn {}", selectedBank.getBankCode(),
                request.getTransactionId());

        // ═══════════ STEP 3: SEND TO BANK (with circuit breaker) ═══════════
        BankResponse bankResponse = sendToBank(request, selectedBank);

        // ═══════════ STEP 4: UPDATE ROUTING SCORES ═══════════
        boolean success = "00".equals(bankResponse.getResponseCode());
        routingService.recordResult(
                selectedBank.getBankCode(),
                success,
                bankResponse.getLatencyMs()
        );

        return ResponseEntity.ok(RoutingResponse.fromBank(bankResponse, fraudResult, selectedBank));
    }

    @CircuitBreaker(name = "bankCommunication", fallbackMethod = "bankFallback")
    private BankResponse sendToBank(RoutingRequest request, BankRoute bank) {
        long startTime = System.currentTimeMillis();

        byte[] isoRequest = buildIsoMessage(request);
        String correlationId = request.getTransactionId().substring(0, 12);

        try {
            CompletableFuture<byte[]> future = bankClient.send(isoRequest, correlationId);
            byte[] responseBytes = future.get();

            long latency = System.currentTimeMillis() - startTime;
            return BankResponse.parse(responseBytes, latency);

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            throw new BankCommunicationException(bank.getBankCode(), latency, e);
        }
    }

    // WHY fallback: When circuit breaker is OPEN, this runs instead of calling the bank
    private BankResponse bankFallback(RoutingRequest request, BankRoute bank, Throwable t) {
        log.error("⚡ Circuit breaker OPEN for bank={}: {}", bank.getBankCode(), t.getMessage());
        return BankResponse.circuitOpen(bank.getBankCode());
    }

    private FraudContext buildFraudContext(RoutingRequest request) {
        return FraudContext.builder()
                .transactionId(request.getTransactionId())
                .cardHash(request.getCardHash())
                .amountInPaise(request.getAmountPaise())
                .cardBin(request.getCardBin())
                .ipCountryCode(request.getIpCountry())
                .cardIssuingCountry(request.getCardCountry())
                .merchantCategory(request.getMcc())
                .customerEmail(request.getEmail())
                .isNewDevice(request.isNewDevice())
                .cardCreatedDaysAgo(request.getCardAgeDays())
                .build();
    }

    private byte[] buildIsoMessage(RoutingRequest request) {
        // Delegate to Iso8583MessageBuilder (from Part 9A)
        return Iso8583MessageBuilder.authorizationRequest()
                .pan(request.getCardNumber())
                .amount(request.getAmountPaise())
                .merchantId(request.getMerchantId())
                .correlationId(request.getTransactionId().substring(0, 12))
                .build();
    }
}
```

---

## 9. Circuit Breaker with Resilience4j

```yaml
# application.yml — Circuit breaker configuration
resilience4j:
  circuitbreaker:
    instances:
      bankCommunication:
        # WHY 50%: If half of requests to a bank fail, something is seriously wrong
        failureRateThreshold: 50

        # WHY 10: Need at least 10 calls before calculating failure rate
        # (avoids tripping on 2 failures out of 3 calls)
        minimumNumberOfCalls: 10

        # WHY OPEN for 30s: Give the bank time to recover before trying again
        waitDurationInOpenState: 30000

        # WHY 5: In HALF_OPEN, try 5 requests to see if bank recovered
        permittedNumberOfCallsInHalfOpenState: 5

        # WHY sliding window: Looks at last 20 calls (not time-based)
        slidingWindowType: COUNT_BASED
        slidingWindowSize: 20

        # WHY record these: Only count real failures (not business declines)
        recordExceptions:
          - java.util.concurrent.TimeoutException
          - java.io.IOException
          - com.payflow.routing.exception.BankCommunicationException
```

---

## 10. Circuit Breaker States Diagram

```
┌───────────────────────────────────────────────────────────────────────────┐
│                     CIRCUIT BREAKER STATE MACHINE                         │
├───────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  ┌──────────┐         failure rate ≥ 50%          ┌──────────┐          │
│  │          │ ───────────────────────────────────→ │          │          │
│  │  CLOSED  │                                      │   OPEN   │          │
│  │          │                                      │          │          │
│  │ (Normal) │                                      │ (Reject  │          │
│  │ All calls│ ←─── success rate ≥ 50% ──────────  │  all     │          │
│  │ go to    │      in HALF_OPEN                    │  calls)  │          │
│  │ bank     │                                      │          │          │
│  └──────────┘                                      └────┬─────┘          │
│       ↑                                                  │               │
│       │                                                  │               │
│       │           ┌─────────────┐                        │               │
│       │           │             │      wait 30 seconds   │               │
│       └────────── │  HALF_OPEN  │ ←─────────────────────┘               │
│    success ≥ 50%  │             │                                        │
│                   │ (Try 5 calls│                                        │
│                   │  to test if │                                        │
│                   │  bank is OK)│                                        │
│                   └─────────────┘                                        │
│                         │                                                │
│                         │ failure rate still ≥ 50%                       │
│                         └────────────→ back to OPEN                      │
│                                                                           │
├───────────────────────────────────────────────────────────────────────────┤
│ Timeline Example:                                                         │
│                                                                           │
│ t=0s   CLOSED: HDFC responds normally (95% success)                      │
│ t=10s  CLOSED: HDFC starts timing out (failure rate climbing)            │
│ t=15s  CLOSED → OPEN: failure rate hits 50% (10/20 calls failed)         │
│ t=15s  OPEN: All calls get fallback response immediately (no bank call)  │
│ t=45s  OPEN → HALF_OPEN: 30s elapsed, let's test HDFC again            │
│ t=45s  HALF_OPEN: Send 5 test transactions to HDFC                      │
│ t=47s  HALF_OPEN: 4/5 succeeded! (80% success rate)                     │
│ t=47s  HALF_OPEN → CLOSED: HDFC is healthy again!                       │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 11. What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | Hybrid Fraud Scoring | 60% rules + 40% ML gives best precision + recall |
| 2 | Velocity Check | Redis INCR with TTL = sliding window rate limiter |
| 3 | Decision Tree Scorer | Feature extraction → weighted sum → normalize to 0-100 |
| 4 | Fraud Thresholds | <30 approve, 30-70 review, >70 decline |
| 5 | Multi-Armed Bandit | Balances exploring new options vs exploiting known-best |
| 6 | Epsilon-Greedy | 10% random (explore) + 90% best (exploit) |
| 7 | Composite Score | 70% success + 20% latency + 10% cost = overall bank quality |
| 8 | Exponential Moving Average | Recent results weighted more than old results |
| 9 | Circuit Breaker | Prevents cascading failures when bank is down |
| 10 | State Machine | CLOSED → OPEN → HALF_OPEN → back to CLOSED or OPEN |

---

## 12. Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `NoEligibleBankException` | No bank supports this card/amount/currency | Add more bank routes or check card BIN configuration |
| `CallNotPermittedException` | Circuit breaker is OPEN | Wait for it to transition to HALF_OPEN (30s default) |
| Fraud score always 0 | Redis not running (velocity rule fails silently) | Start Redis and check connection config |
| All traffic to one bank | Epsilon too low or only 1 bank configured | Check EPSILON value and bank route configuration |
| Score > 100 | Weights don't sum to 1.0 or features not normalized | Ensure features are 0-100 and weights sum to 1.0 |
| `NullPointerException` in geo rule | IP country code not resolved | Add null check before lookup (already handled above) |
| Circuit breaker never opens | `minimumNumberOfCalls` too high | Lower it or increase traffic for testing |
| Bank timeout causes decline | Circuit breaker records timeout as failure | Separate timeout handling from business decline |

---

## 13. Git Commit

```bash
# Stage fraud detection and smart routing files
git add backend/routing-service/src/main/java/com/payflow/routing/fraud/
git add backend/routing-service/src/main/java/com/payflow/routing/smartroute/
git add backend/routing-service/src/main/java/com/payflow/routing/controller/RoutingController.java
git add backend/routing-service/src/main/resources/application.yml

# Commit
git commit -m "feat(routing): add fraud detection and smart routing with circuit breaker

- FraudDetectionService: hybrid scoring (60% rules + 40% ML)
- RuleEngine: velocity check, amount threshold, geo-blocking
- DecisionTreeScorer: feature extraction + weighted scoring
- SmartRoutingService: multi-armed bandit epsilon-greedy algorithm
- BankRoute: composite score (success rate + latency + cost)
- RoutingController: orchestrates fraud → route → bank flow
- Resilience4j CircuitBreaker on bank communication
- Exponential moving average for adaptive bank scoring"

# Push
git push origin feature/phase4-routing-service
```

---

## Document Index

| # | Document | Status |
|---|----------|--------|
| 01 | Project Overview & Architecture | ✅ |
| 02 | Development Environment Setup | ✅ |
| 03 | Merchant Service (CRUD + Auth) | ✅ |
| 04 | Payment Service (Core Processing) | ✅ |
| 05 | API Gateway (Routing + Security) | ✅ |
| 06 | Kafka Event Streaming | ✅ |
| 07 | Redis Caching & Idempotency | ✅ |
| 08 | Ledger Service (Double-Entry) | ✅ |
| 09a | ISO 8583 Message Parsing | ✅ |
| 09b | Netty TCP Client | ✅ |
| **09c** | **Fraud Detection & Smart Routing** | **📍 Current** |
| 10 | Bank Simulator | 🔜 Next |
| 11 | Settlement Service | ⬜ |
| 12 | Webhook Service | ⬜ |
| 13 | Notification Service | ⬜ |
| 14 | Docker & Containerization | ⬜ |
| 15a | Frontend Setup | ⬜ |
| 15b | Frontend Features | ⬜ |

---

## Next Steps

In **Part 10**, we'll build the **Bank Simulator** — a fake bank that:
- Runs as a Netty TCP SERVER (opposite of our client)
- Receives ISO 8583 auth requests
- Returns approve/decline based on configurable rules
- Simulates real bank latency (200-2000ms random delay)

This lets us test the entire routing pipeline without connecting to real Visa/HDFC!

---

[← Previous: Part 9B — Netty TCP Client](./phase4-part09b-routing-netty.md) | [Next: Part 10 — Bank Simulator →](./phase4-part10-bank-simulator.md)
