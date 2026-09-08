# 🏗️ Phase 4 Part 9h: Routing Service — Smart Routing (Epsilon-Greedy Multi-Armed Bandit)

> **"90% of the time, pick the best bank. 10% of the time, pick a random one. That's how you optimize performance while still discovering improvements."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 9h — Smart Routing |
| **What You Build** | BankRoute.java, RoutingDecision.java, RoutingMetricsRepository.java, SmartRoutingService.java, SmartRoutingServiceTest.java |
| **Previous** | [Part 9g — Fraud ML + Service](./phase4-part09g-fraud-ml-service.md) |
| **Next** | [Part 9i — Config Classes](./phase4-part09i-config-classes.md) |

---

## 📖 Table of Contents

1. [What Is a Multi-Armed Bandit?](#1-what-is-a-multi-armed-bandit)
2. [Why Epsilon-Greedy for Bank Routing?](#2-why-epsilon-greedy-for-bank-routing)
3. [Folder Structure After This Part](#3-folder-structure-after-this-part)
4. [Step-by-Step: BankRoute.java](#4-step-by-step-bankroutejava)
5. [Step-by-Step: RoutingDecision.java](#5-step-by-step-routingdecisionjava)
6. [Step-by-Step: RoutingMetricsRepository.java](#6-step-by-step-routingmetricsrepositoryjava)
7. [Step-by-Step: SmartRoutingService.java](#7-step-by-step-smartroutingservicejava)
8. [Step-by-Step: SmartRoutingServiceTest.java](#8-step-by-step-smartroutingservicetestjava)
9. [Explore vs Exploit — Why Both Matter](#9-explore-vs-exploit--why-both-matter)
10. [What You Learned](#10-what-you-learned)

---

## 1. What Is a Multi-Armed Bandit?

```
CASINO ANALOGY:

You're at a casino with 4 slot machines ("bandits" = arms).
Each has a DIFFERENT payout rate, but you DON'T know which is best.

Machine A: pays out 95% of the time (BEST — but you don't know yet)
Machine B: pays out 88%
Machine C: pays out 92%
Machine D: pays out 85%

PURE EXPLOIT (always play what seems best so far):
  After 10 plays on each:
  A seems best → play A 1,000 more times → great! (95%)
  BUT: What if C secretly upgraded to 98%? You'll never discover it.

PURE EXPLORE (play random every time):
  Play A, C, D, B, C, A, D, B, A, D...
  You discover C is now 98%! But you also play D (85%) too often.

EPSILON-GREEDY (best of both):
  90% of the time → play the best-known machine (EXPLOIT)
  10% of the time → play a random machine (EXPLORE)
  Result: Mostly get high payouts, but sometimes discover improvements
```

**FOR PAYFLOW:**
- "Machines" = bank routes (Alpha Bank, Beta Bank, Gamma Bank, Delta Bank)
- "Payout" = successful transaction (bank approved the payment)
- "Explore" = send to a random bank (10% of transactions)
- "Exploit" = send to the bank with highest success rate (90% of transactions)

---

## 2. Why Epsilon-Greedy for Bank Routing?

```
REAL-WORLD SCENARIO:

Day 1: Alpha Bank has 95% success rate → gets 90% of traffic
Day 30: Alpha Bank has scheduled maintenance → success rate drops to 70%
        Gamma Bank upgraded servers → success rate improved to 98%

WITHOUT exploration:
  100% traffic → Alpha Bank (now 70%) → 30% of payments fail!
  We NEVER discover that Gamma is now 98%

WITH epsilon-greedy (10% exploration):
  90% → Alpha Bank (70%) → many failures detected
  10% → random → some go to Gamma → 98% success!
  
  After 50 transactions:
  Algorithm sees: "Gamma has 98% success in recent transactions"
  AUTOMATIC switch: Gamma becomes the exploit choice
  90% → Gamma Bank (98%) → payments flow smoothly again

  No human intervention needed. The algorithm ADAPTS.
```

**WHO ELSE USES THIS ALGORITHM?**
- **Google** — A/B testing ad placements
- **Netflix** — recommending shows
- **Uber** — selecting optimal ride routes
- **Amazon** — product recommendation ordering
- **Clinical trials** — allocating patients to treatments

---

## 3. Folder Structure After This Part

```
backend/routing-service/src/
├── main/java/com/payflow/routing/
│   ├── fraud/                           ← from 9f, 9g
│   ├── routing/                         ← YOU CREATE THIS FOLDER
│   │   ├── BankRoute.java              ← YOU CREATE THIS
│   │   ├── RoutingDecision.java        ← YOU CREATE THIS
│   │   └── RoutingMetricsRepository.java ← YOU CREATE THIS
│   └── service/
│       ├── FraudDetectionService.java   ← from 9g
│       └── SmartRoutingService.java     ← YOU CREATE THIS
└── test/java/com/payflow/routing/service/
    ├── FraudDetectionServiceTest.java   ← from 9g
    └── SmartRoutingServiceTest.java     ← YOU CREATE THIS
```

---

## 4. Step-by-Step: BankRoute.java

**File:** `src/main/java/com/payflow/routing/routing/BankRoute.java`

The data model for a bank's routing metrics.

### Full Source Code

```java
package com.payflow.routing.routing;

/**
 * Represents a bank route with performance metrics for smart routing decisions.
 */
public class BankRoute {

    private String bankId;
    private String bankName;
    private double successRate;
    private double avgLatencyMs;
    private double costPerTxn;
    private boolean active;
```

**6 FIELDS — what the routing algorithm uses to decide:**

| Field | Type | Example | Used For |
|---|---|---|---|
| `bankId` | String | `"bank-alpha"` | Unique identifier |
| `bankName` | String | `"Alpha Bank"` | Logging and display |
| `successRate` | double | `0.95` (95%) | **PRIMARY** selection criterion |
| `avgLatencyMs` | double | `120.0` (120ms) | **TIEBREAKER** when success rates are equal |
| `costPerTxn` | double | `0.25` (₹0.25) | **SECOND TIEBREAKER** — cheaper is better |
| `active` | boolean | `true` | Only active banks receive traffic |

**WHY PLAIN JAVA CLASS (not record, not @Data)?**

| Approach | Why Not |
|---|---|
| Record | BankRoute is **mutable** — `successRate` and `avgLatencyMs` change after each transaction |
| @Data (Lombok) | Would work, but routing service uses plain Java classes for models (style choice) |
| Manual getters/setters | Full control, no dependencies, explicit what's mutable |

```java
    public BankRoute() {
    }

    public BankRoute(String bankId, String bankName, double successRate,
                     double avgLatencyMs, double costPerTxn, boolean active) {
        this.bankId = bankId;
        this.bankName = bankName;
        this.successRate = successRate;
        this.avgLatencyMs = avgLatencyMs;
        this.costPerTxn = costPerTxn;
        this.active = active;
    }
```

**TWO CONSTRUCTORS:**
- No-arg: for frameworks (serialization, DynamoDB enhanced client)
- All-arg: for programmatic creation (tests, in-memory initialization)

```java
    // ... standard getters and setters for all 6 fields ...

    @Override
    public String toString() {
        return "BankRoute{" +
                "bankId='" + bankId + '\'' +
                ", bankName='" + bankName + '\'' +
                ", successRate=" + successRate +
                ", avgLatencyMs=" + avgLatencyMs +
                ", costPerTxn=" + costPerTxn +
                ", active=" + active +
                '}';
    }
}
```

---

## 5. Step-by-Step: RoutingDecision.java

**File:** `src/main/java/com/payflow/routing/routing/RoutingDecision.java`

The output of the routing algorithm — which bank was chosen and why.

### Full Source Code

```java
package com.payflow.routing.routing;

/**
 * Result of a routing decision made by the Smart Routing Service.
 *
 * @param selectedBank The bank route chosen for this transaction
 * @param reason       Human-readable reason for the selection
 * @param isExplore    Whether this was an exploration choice (epsilon-greedy)
 */
public record RoutingDecision(
        BankRoute selectedBank,
        String reason,
        boolean isExplore
) {
```

**A RECORD WITH 3 FIELDS:**

| Field | Type | Purpose |
|---|---|---|
| `selectedBank` | BankRoute | Which bank was chosen |
| `reason` | String | Human-readable explanation |
| `isExplore` | boolean | Was this an exploration (random) or exploitation (best bank)? |

**WHY RECORD?** A routing decision is **immutable** — once made, it doesn't change. Perfect for a record.

```java
    /**
     * Creates an exploitation decision (chose best bank).
     */
    public static RoutingDecision exploit(BankRoute bank) {
        return new RoutingDecision(bank, "Selected highest performing bank (exploit)", false);
    }

    /**
     * Creates an exploration decision (random bank selection).
     */
    public static RoutingDecision explore(BankRoute bank) {
        return new RoutingDecision(bank, "Random bank selection for exploration (explore)", true);
    }
}
```

**FACTORY METHODS** — create decisions with appropriate reasons:

| Method | isExplore | reason |
|---|---|---|
| `RoutingDecision.exploit(bank)` | `false` | "Selected highest performing bank (exploit)" |
| `RoutingDecision.explore(bank)` | `true` | "Random bank selection for exploration (explore)" |

The controller logs `decision.isExplore()` to track how often exploration happens.

---

## 6. Step-by-Step: RoutingMetricsRepository.java

**File:** `src/main/java/com/payflow/routing/routing/RoutingMetricsRepository.java`

An interface defining how bank metrics are stored and retrieved.

### Full Source Code

```java
package com.payflow.routing.routing;

import java.util.List;

/**
 * Repository interface for storing and retrieving routing metrics.
 * Can be backed by DynamoDB or in-memory storage.
 */
public interface RoutingMetricsRepository {

    /**
     * Returns all configured bank routes with their metrics.
     */
    List<BankRoute> getAllBankRoutes();

    /**
     * Returns all active bank routes.
     */
    List<BankRoute> getActiveBankRoutes();

    /**
     * Gets a specific bank route by ID.
     */
    BankRoute getBankRoute(String bankId);

    /**
     * Updates the success rate for a bank after a transaction.
     *
     * @param bankId  Bank identifier
     * @param success Whether the transaction was successful
     * @param latencyMs Response latency in milliseconds
     */
    void recordTransactionResult(String bankId, boolean success, long latencyMs);

    /**
     * Updates the bank route metrics (success rate, latency).
     */
    void updateBankRoute(BankRoute bankRoute);

    /**
     * Returns the total transaction count for a bank.
     */
    long getTransactionCount(String bankId);
}
```

**AN INTERFACE, NOT A CLASS** — this is the **Repository Pattern** (same concept as JPA repositories, but without JPA):

| JPA Repository (payment-service) | This Repository (routing-service) |
|---|---|
| `extends JpaRepository<Entity, ID>` | Custom interface (no JPA) |
| Implementation auto-generated by Spring Data | Implementation in `DynamoDbConfig` (Part 9i) |
| Backed by PostgreSQL | Backed by DynamoDB or in-memory `ConcurrentHashMap` |

**6 METHODS the smart routing algorithm needs:**

| Method | Used By | Purpose |
|---|---|---|
| `getActiveBankRoutes()` | `SmartRoutingService.selectRoute()` | Get list of banks to choose from |
| `recordTransactionResult()` | `SmartRoutingService.recordResult()` | Update bank's success rate after a txn |
| `getAllBankRoutes()` | Admin/monitoring | View all banks including inactive |
| `getBankRoute(id)` | Metrics lookup | Get specific bank's performance |
| `updateBankRoute()` | Admin | Manually update bank config |
| `getTransactionCount()` | Monitoring | Total transactions processed by a bank |

**WHY INTERFACE?** Allows swapping implementations:
- **Dev/Test:** `InMemoryRoutingMetricsRepository` (ConcurrentHashMap) — Part 9i
- **Production:** `DynamoDbRoutingMetricsRepository` (AWS DynamoDB) — future

---

## 7. Step-by-Step: SmartRoutingService.java

**File:** `src/main/java/com/payflow/routing/service/SmartRoutingService.java`

The core algorithm — epsilon-greedy bank selection.

### Full Source Code

```java
package com.payflow.routing.service;

import com.payflow.routing.routing.BankRoute;
import com.payflow.routing.routing.RoutingDecision;
import com.payflow.routing.routing.RoutingMetricsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
```

**KEY IMPORT: `ThreadLocalRandom`** — thread-safe random number generator. Unlike `Random`, `ThreadLocalRandom` doesn't have contention when multiple threads generate random numbers simultaneously. Perfect for a high-throughput routing service.

```java
/**
 * Smart routing service implementing the multi-armed bandit epsilon-greedy algorithm.
 * <p>
 * Strategy:
 * - Explore (epsilon probability, default 10%): Select a random bank to gather performance data.
 * - Exploit (1-epsilon probability, default 90%): Select the bank with the highest success rate.
 * <p>
 * This approach balances discovering better routes with exploiting known good ones.
 */
@Service
public class SmartRoutingService {

    private static final Logger log = LoggerFactory.getLogger(SmartRoutingService.class);

    private final RoutingMetricsRepository metricsRepository;

    @Value("${routing.epsilon:0.10}")
    private double epsilon;
```

**`epsilon = 0.10`** — from `application.yml`. The probability of exploration. `0.10` = 10% explore, 90% exploit.

```java
    public SmartRoutingService(RoutingMetricsRepository metricsRepository) {
        this.metricsRepository = metricsRepository;
    }
```

**SINGLE DEPENDENCY** — the repository that provides bank routes and stores metrics. Constructor injection.

### selectRoute() — The Core Algorithm

```java
    /**
     * Selects the best bank route using epsilon-greedy strategy.
     *
     * @return RoutingDecision with selected bank and exploration flag
     * @throws IllegalStateException if no active banks are available
     */
    public RoutingDecision selectRoute() {
        List<BankRoute> activeBanks = metricsRepository.getActiveBankRoutes();

        if (activeBanks.isEmpty()) {
            throw new IllegalStateException("No active bank routes available for routing");
        }

        // Single bank? No choice needed.
        if (activeBanks.size() == 1) {
            return RoutingDecision.exploit(activeBanks.get(0));
        }

        double random = ThreadLocalRandom.current().nextDouble();

        if (random < epsilon) {
            // Explore: pick a random bank
            return explore(activeBanks);
        } else {
            // Exploit: pick the best performing bank
            return exploit(activeBanks);
        }
    }
```

**THE ALGORITHM IN 4 LINES:**

```
1. Get active banks
2. If only 1 bank → use it (no choice)
3. Generate random number 0.0 to 1.0
4. If random < 0.10 → EXPLORE (random bank)
   If random >= 0.10 → EXPLOIT (best bank)
```

**`ThreadLocalRandom.current().nextDouble()`** — returns a double between 0.0 (inclusive) and 1.0 (exclusive).

**EDGE CASE: Single bank.** If there's only one active bank, no explore/exploit decision is needed. Return it as an exploit decision (it IS the best and only option).

### explore() — Random Selection

```java
    /**
     * Exploration: Selects a random bank for data gathering.
     */
    private RoutingDecision explore(List<BankRoute> banks) {
        int randomIndex = ThreadLocalRandom.current().nextInt(banks.size());
        BankRoute selectedBank = banks.get(randomIndex);

        log.info("EXPLORE: Randomly selected bank '{}' (epsilon={})",
                selectedBank.getBankName(), epsilon);

        return RoutingDecision.explore(selectedBank);
    }
```

**UNIFORM RANDOM:** Each bank has equal probability of being selected. With 4 banks, each has 25% chance during exploration.

**WHY EXPLORE?** The currently "worst" bank might have improved. Without exploration, you'd never discover that.

### exploit() — Best Bank Selection

```java
    /**
     * Exploitation: Selects the bank with the highest success rate.
     * If success rates are equal, prefer lower latency. If latency is also equal, prefer lower cost.
     */
    private RoutingDecision exploit(List<BankRoute> banks) {
        BankRoute bestBank = banks.stream()
                .max(Comparator.comparingDouble(BankRoute::getSuccessRate)
                        .thenComparing(Comparator.comparingDouble(BankRoute::getAvgLatencyMs).reversed())
                        .thenComparing(Comparator.comparingDouble(BankRoute::getCostPerTxn).reversed()))
                .orElseThrow(() -> new IllegalStateException("No banks to exploit"));

        log.info("EXPLOIT: Selected best bank '{}' (successRate={:.2f}%, avgLatency={:.0f}ms)",
                bestBank.getBankName(), bestBank.getSuccessRate() * 100, bestBank.getAvgLatencyMs());

        return RoutingDecision.exploit(bestBank);
    }
```

**THE COMPARATOR CHAIN — 3-level tiebreaking:**

```
Level 1: Highest success rate       (MAX — higher is better)
Level 2: Lowest latency             (reversed — lower is better)
Level 3: Lowest cost                (reversed — lower is better)

Example with 4 banks:
  Alpha: 95% / 120ms / ₹0.25
  Beta:  88% / 200ms / ₹0.15
  Gamma: 92% / 150ms / ₹0.20
  Delta: 85% / 300ms / ₹0.10

  Level 1: Alpha wins (95% > 92% > 88% > 85%)
  → Alpha Bank selected

If Alpha and Gamma both had 95%:
  Level 2: Alpha wins (120ms < 150ms — lower is better)

If both 95% and 120ms:
  Level 3: Cheaper one wins
```

**`Comparator.comparingDouble(BankRoute::getSuccessRate)`** — sorts by success rate ascending. `.max()` picks the highest.

**`.thenComparing(...reversed())`** — for latency and cost, LOWER is better. Since `.max()` picks the highest, we `.reversed()` these so the LOWEST original value becomes the "highest" in the comparator.

### recordResult() — Learning From Each Transaction

```java
    /**
     * Records the result of a transaction for future routing decisions.
     *
     * @param bankId    Bank that processed the transaction
     * @param success   Whether the transaction was approved
     * @param latencyMs Response time in milliseconds
     */
    public void recordResult(String bankId, boolean success, long latencyMs) {
        metricsRepository.recordTransactionResult(bankId, success, latencyMs);
        log.debug("Recorded result for bank={}: success={}, latency={}ms", bankId, success, latencyMs);
    }
```

**THIS IS THE "LEARNING" STEP.** After every transaction, the algorithm records:
- Did the bank approve? (success rate goes up or down)
- How fast was the bank? (latency average updated)

Over time, these metrics change the routing decisions:
```
Day 1: Alpha 95%, Gamma 92% → Alpha gets 90% traffic
Day 30: Alpha drops to 88%, Gamma improves to 97% → Gamma now gets 90% traffic
No code changes needed — the algorithm adapts automatically.
```

```java
    /**
     * Returns the current epsilon value.
     */
    public double getEpsilon() {
        return epsilon;
    }

    /**
     * Sets the epsilon value (for testing or dynamic adjustment).
     */
    public void setEpsilon(double epsilon) {
        this.epsilon = epsilon;
    }
}
```

**`setEpsilon()`** — allows tests to control explore/exploit behavior. Also allows future dynamic adjustment (e.g., increase epsilon during incidents to discover healthier banks faster).

---

## 8. Step-by-Step: SmartRoutingServiceTest.java

**File:** `src/test/java/com/payflow/routing/service/SmartRoutingServiceTest.java`

5 tests covering all scenarios of the epsilon-greedy algorithm.

### Setup

```java
package com.payflow.routing.service;

import com.payflow.routing.routing.BankRoute;
import com.payflow.routing.routing.RoutingDecision;
import com.payflow.routing.routing.RoutingMetricsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SmartRoutingService Unit Tests")
class SmartRoutingServiceTest {

    @Mock
    private RoutingMetricsRepository metricsRepository;

    private SmartRoutingService smartRoutingService;

    private BankRoute hdfc;
    private BankRoute icici;
    private BankRoute axis;

    @BeforeEach
    void setUp() {
        smartRoutingService = new SmartRoutingService(metricsRepository);

        hdfc = new BankRoute("hdfc-001", "HDFC Bank", 0.95, 120.0, 1.5, true);
        icici = new BankRoute("icici-001", "ICICI Bank", 0.88, 150.0, 1.2, true);
        axis = new BankRoute("axis-001", "Axis Bank", 0.80, 200.0, 1.0, true);
    }
```

**3 TEST BANKS with different characteristics:**

| Bank | Success Rate | Latency | Cost | Rank |
|---|---|---|---|---|
| HDFC | 95% | 120ms | ₹1.50 | #1 (highest success) |
| ICICI | 88% | 150ms | ₹1.20 | #2 |
| Axis | 80% | 200ms | ₹1.00 | #3 |

### Test 1: Exploit Selects Best Bank

```java
    @Test
    @DisplayName("selectRoute - exploit should select bank with highest success rate")
    void selectRoute_Exploit_SelectsBestBank() {
        // Set epsilon to 0 to always exploit
        smartRoutingService.setEpsilon(0.0);
        when(metricsRepository.getActiveBankRoutes()).thenReturn(List.of(hdfc, icici, axis));

        RoutingDecision decision = smartRoutingService.selectRoute();

        assertThat(decision).isNotNull();
        assertThat(decision.selectedBank().getBankId()).isEqualTo("hdfc-001");
        assertThat(decision.selectedBank().getSuccessRate()).isEqualTo(0.95);
        assertThat(decision.isExplore()).isFalse();
    }
```

**`setEpsilon(0.0)`** — forces 100% exploitation. `random < 0.0` is never true → always exploits.

**VERIFIES:** With epsilon=0, the bank with highest success rate (HDFC 95%) is always selected.

### Test 2: Explore Selects Random Bank

```java
    @Test
    @DisplayName("selectRoute - explore should select a random bank")
    void selectRoute_Explore_SelectsRandomBank() {
        // Set epsilon to 1.0 to always explore
        smartRoutingService.setEpsilon(1.0);
        when(metricsRepository.getActiveBankRoutes()).thenReturn(List.of(hdfc, icici, axis));

        RoutingDecision decision = smartRoutingService.selectRoute();

        assertThat(decision).isNotNull();
        assertThat(decision.isExplore()).isTrue();
        assertThat(decision.selectedBank()).isIn(hdfc, icici, axis);
    }
```

**`setEpsilon(1.0)`** — forces 100% exploration. `random < 1.0` is always true → always explores.

**`isIn(hdfc, icici, axis)`** — the selected bank must be one of the three (we can't predict which one due to randomness).

### Test 3: No Active Banks → Exception

```java
    @Test
    @DisplayName("selectRoute - should throw IllegalStateException when no active banks")
    void selectRoute_NoBanks_ThrowsException() {
        when(metricsRepository.getActiveBankRoutes()).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> smartRoutingService.selectRoute())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active bank routes");
    }
```

**EDGE CASE:** If all banks are down/deactivated, throw an exception. The controller catches this and returns an error to Payment Service.

### Test 4: Single Bank → No Explore/Exploit

```java
    @Test
    @DisplayName("selectRoute - single bank should return that bank without explore/exploit logic")
    void selectRoute_SingleBank_ReturnsThatBank() {
        when(metricsRepository.getActiveBankRoutes()).thenReturn(List.of(icici));

        RoutingDecision decision = smartRoutingService.selectRoute();

        assertThat(decision).isNotNull();
        assertThat(decision.selectedBank().getBankId()).isEqualTo("icici-001");
        assertThat(decision.isExplore()).isFalse();
    }
```

**EDGE CASE:** Only one active bank → return it directly as exploit (it IS the best option by default).

### Test 5: Equal Success Rates → Prefer Lower Latency

```java
    @Test
    @DisplayName("selectRoute - exploit with equal success rates prefers lower latency")
    void selectRoute_Exploit_EqualSuccessRate_PrefersLowerLatency() {
        smartRoutingService.setEpsilon(0.0);

        BankRoute bankA = new BankRoute("bank-a", "Bank A", 0.90, 100.0, 1.5, true);
        BankRoute bankB = new BankRoute("bank-b", "Bank B", 0.90, 200.0, 1.5, true);

        when(metricsRepository.getActiveBankRoutes()).thenReturn(List.of(bankA, bankB));

        RoutingDecision decision = smartRoutingService.selectRoute();

        assertThat(decision).isNotNull();
        // Bank A has lower latency, should be selected when success rates are equal
        assertThat(decision.selectedBank().getBankId()).isEqualTo("bank-a");
    }
```

**TESTS THE TIEBREAKER:** Both banks have 90% success rate. Bank A has 100ms latency vs Bank B's 200ms. Bank A wins on the latency tiebreaker.

---

## 9. Explore vs Exploit — Why Both Matter

```
SCENARIO: 4 banks, 100 transactions over time

EPSILON = 0 (pure exploit — no exploration):
  Txn 1-100: All → Alpha (95%)
  
  Alpha upgrades servers → 98% ← Never discovered!
  Gamma degrades → 70%          ← Never noticed!
  Result: Stuck with 95% forever, missing 98% opportunity.

EPSILON = 1 (pure explore — no exploitation):
  Txn 1-100: 25 → Alpha (95%), 25 → Beta (88%), 25 → Gamma (92%), 25 → Delta (85%)
  
  Average success: (95+88+92+85)/4 = 90%
  Result: 10% of traffic to the worst bank! Wasted.

EPSILON = 0.10 (our choice — balanced):
  Txn 1-90: All → Alpha (95%)     ← EXPLOIT: use what we know
  Txn 91-100: Random split          ← EXPLORE: discover changes
    Txn 91: → Beta (88%)  ✅ record
    Txn 92: → Alpha (95%) ✅ record
    Txn 93: → Gamma (92%) ✅ record  ← might discover Gamma improved!
    ...
  
  After 1000 txns: ~900 to best bank, ~100 split among others
  If Gamma improves to 98%: exploration discovers it in ~25 txns
  Algorithm automatically switches: Gamma becomes the exploit choice
  
  Result: 90%+ success rate, continuous adaptation, no human intervention.
```

---

## 10. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **Multi-armed bandit** | Algorithm that balances trying new options (explore) with using known-best (exploit) |
| 2 | **Epsilon-greedy** | With probability ε explore randomly, with 1-ε exploit the best option |
| 3 | **Why ε = 0.10** | 10% exploration = enough to detect changes, 90% exploitation = high success rate |
| 4 | **BankRoute** | Mutable POJO with 6 fields: bankId, bankName, successRate, avgLatencyMs, costPerTxn, active |
| 5 | **Why not record** | BankRoute is mutable (metrics change after each txn). Records are immutable |
| 6 | **RoutingDecision record** | Immutable decision: selectedBank + reason + isExplore flag |
| 7 | **Factory methods** | `RoutingDecision.exploit(bank)` and `.explore(bank)` — clean creation |
| 8 | **RoutingMetricsRepository interface** | Repository pattern without JPA — allows DynamoDB or in-memory implementations |
| 9 | **ThreadLocalRandom** | Thread-safe random — no contention in multi-threaded routing |
| 10 | **Comparator chaining** | `.max(comparingDouble(successRate).thenComparing(latency.reversed()).thenComparing(cost.reversed()))` |
| 11 | **`.reversed()` for "lower is better"** | Since `.max()` picks highest, reverse latency/cost so lowest becomes highest |
| 12 | **`setEpsilon()` for testing** | Control explore/exploit behavior deterministically in tests |
| 13 | **ε=0 in tests** | Forces 100% exploitation → predictable test outcomes |
| 14 | **ε=1 in tests** | Forces 100% exploration → `isIn(bank1, bank2, bank3)` asserts any is valid |
| 15 | **Edge case: empty banks** | `IllegalStateException` — can't route if no banks available |
| 16 | **Edge case: single bank** | Skip explore/exploit logic — just return it |
| 17 | **recordResult() for learning** | Update bank metrics after each transaction — this is how the algorithm adapts |
| 18 | **Where else it's used** | Google ads, Netflix recommendations, Uber routing, clinical trials |

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
| [Part 9g](./phase4-part09g-fraud-ml-service.md) | Fraud ML + Service + Tests |
| **Part 9h** | **Smart Routing + Tests** (You are here) |
| [Part 9i](./phase4-part09i-config-classes.md) | Config Classes (DynamoDB, Resilience4j) |
| [Part 9j](./phase4-part09j-controller-docker.md) | Controller + DTOs + Docker + curl |
| [Part 9k](./phase4-part09k-connections-flows.md) | How Everything Connects |

---

*Next: [Part 9i — Config Classes (DynamoDbConfig, Resilience4jConfig)](./phase4-part09i-config-classes.md) →*
