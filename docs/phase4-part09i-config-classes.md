# 🏗️ Phase 4 Part 9i: Routing Service — Config Classes (DynamoDbConfig, Resilience4jConfig)

> **"DynamoDbConfig provides the data. Resilience4jConfig protects the calls. Together they make smart routing work in production."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 9i — Config Classes |
| **What You Build** | DynamoDbConfig.java, Resilience4jConfig.java |
| **Previous** | [Part 9h — Smart Routing](./phase4-part09h-smart-routing.md) |
| **Next** | [Part 9j — Controller + DTOs + Docker](./phase4-part09j-controller-docker.md) |

---

## 📖 Table of Contents

1. [Why Two Config Classes?](#1-why-two-config-classes)
2. [Folder Structure After This Part](#2-folder-structure-after-this-part)
3. [Step-by-Step: DynamoDbConfig.java](#3-step-by-step-dynamodbconfigjava)
4. [Step-by-Step: Resilience4jConfig.java](#4-step-by-step-resilience4jconfigjava)
5. [What You Learned](#5-what-you-learned)

---

## 1. Why Two Config Classes?

| Config | What It Creates | Used By |
|---|---|---|
| `DynamoDbConfig` | DynamoDbClient bean + InMemoryRoutingMetricsRepository bean | SmartRoutingService (bank metrics) |
| `Resilience4jConfig` | CircuitBreakerRegistry bean with event listeners | RoutingController (@CircuitBreaker annotation) |

**These are the last two infrastructure pieces** before the controller can orchestrate everything.

---

## 2. Folder Structure After This Part

```
backend/routing-service/src/main/java/com/payflow/routing/config/
├── NettyConfig.java              ← from 9d
├── DynamoDbConfig.java           ← YOU CREATE THIS
└── Resilience4jConfig.java       ← YOU CREATE THIS
```

---

## 3. Step-by-Step: DynamoDbConfig.java

**File:** `src/main/java/com/payflow/routing/config/DynamoDbConfig.java`

This file does TWO things: creates a DynamoDbClient AND provides an in-memory metrics repository for development.

### Full Source Code

```java
package com.payflow.routing.config;

import com.payflow.routing.routing.BankRoute;
import com.payflow.routing.routing.RoutingMetricsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
```

**AWS SDK IMPORTS:** `DynamoDbClient`, `AwsBasicCredentials`, `StaticCredentialsProvider`, `Region` — all from AWS SDK v2.

```java
/**
 * DynamoDB configuration with configurable endpoint for LocalStack support.
 */
@Configuration
public class DynamoDbConfig {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbConfig.class);

    @Value("${aws.dynamodb.endpoint:http://localhost:4566}")
    private String dynamoDbEndpoint;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${aws.access-key:localstack}")
    private String accessKey;

    @Value("${aws.secret-key:localstack}")
    private String secretKey;
```

**4 CONFIGURABLE AWS PROPERTIES** — all point to LocalStack in development:

| Property | Default | In Dev | In Production |
|---|---|---|---|
| `dynamoDbEndpoint` | `http://localhost:4566` | LocalStack on localhost | `https://dynamodb.us-east-1.amazonaws.com` |
| `awsRegion` | `us-east-1` | Any (LocalStack ignores it) | Actual AWS region |
| `accessKey` | `localstack` | Fake (LocalStack accepts anything) | Real IAM access key |
| `secretKey` | `localstack` | Fake | Real IAM secret key |

### DynamoDbClient Bean

```java
    /**
     * Creates DynamoDB client bean with configurable endpoint.
     * In development/test, this connects to LocalStack.
     */
    @Bean
    public DynamoDbClient dynamoDbClient() {
        log.info("Configuring DynamoDB client with endpoint: {}", dynamoDbEndpoint);

        return DynamoDbClient.builder()
                .endpointOverride(URI.create(dynamoDbEndpoint))
                .region(Region.of(awsRegion))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }
```

**AWS SDK v2 BUILDER PATTERN:**

| Method | What It Does |
|---|---|
| `.endpointOverride(URI)` | Override the default AWS endpoint — connect to LocalStack instead |
| `.region(Region.of("us-east-1"))` | AWS region (required even with LocalStack) |
| `.credentialsProvider(StaticCredentialsProvider)` | Hardcoded credentials (LocalStack accepts any) |

**`StaticCredentialsProvider`** — provides credentials directly (not from environment/IAM role). In production, you'd use `DefaultCredentialsProvider` which reads from environment variables, IAM roles, or `~/.aws/credentials`.

**NOTE:** This bean is created but NOT currently used by the in-memory repository. It's here for when you implement a real DynamoDB-backed repository.

### InMemoryRoutingMetricsRepository — The Dev Implementation

```java
    /**
     * In-memory implementation of RoutingMetricsRepository for development.
     * Provides pre-configured bank routes for testing.
     */
    @Bean
    @Profile({"dev", "default", "docker"})
    public RoutingMetricsRepository inMemoryMetricsRepository() {
        return new InMemoryRoutingMetricsRepository();
    }
```

**🆕 `@Profile({"dev", "default", "docker"})` — CONDITIONAL BEAN CREATION**

| Profile | When Active | This Bean Created? |
|---|---|---|
| `dev` | `spring.profiles.active: dev` (our application.yml) | ✅ Yes |
| `default` | No profile specified | ✅ Yes |
| `docker` | Docker Compose sets this | ✅ Yes |
| `prod` | Production AWS | ❌ No — a DynamoDB-backed implementation would be used |

**This is the SAME pattern as Payment Service's `@Profile("!aws")` for KafkaEventPublisher.** Different profiles activate different implementations.

### The In-Memory Implementation (Nested Class)

```java
    /**
     * In-memory repository implementation with default bank routes.
     */
    static class InMemoryRoutingMetricsRepository implements RoutingMetricsRepository {

        private final Map<String, BankRoute> bankRoutes = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> successCounts = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> totalCounts = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> totalLatency = new ConcurrentHashMap<>();
```

**4 CONCURRENT MAPS for thread-safe metrics tracking:**

| Map | Key | Value | Purpose |
|---|---|---|---|
| `bankRoutes` | bankId | BankRoute | Bank configuration and current metrics |
| `successCounts` | bankId | AtomicLong | How many successful transactions |
| `totalCounts` | bankId | AtomicLong | Total transactions (success + failure) |
| `totalLatency` | bankId | AtomicLong | Sum of all latencies (for calculating average) |

**`AtomicLong`** — thread-safe counter. `incrementAndGet()` and `addAndGet()` are atomic operations — no synchronized block needed.

```java
        InMemoryRoutingMetricsRepository() {
            // Initialize with default bank routes
            addBank(new BankRoute("bank-alpha", "Alpha Bank", 0.95, 120, 0.25, true));
            addBank(new BankRoute("bank-beta", "Beta Bank", 0.88, 200, 0.15, true));
            addBank(new BankRoute("bank-gamma", "Gamma Bank", 0.92, 150, 0.20, true));
            addBank(new BankRoute("bank-delta", "Delta Bank", 0.85, 300, 0.10, true));
        }
```

**4 PRE-CONFIGURED BANKS for development:**

| Bank | Success Rate | Avg Latency | Cost/Txn | Active |
|---|---|---|---|---|
| Alpha Bank | 95% | 120ms | ₹0.25 | ✅ |
| Beta Bank | 88% | 200ms | ₹0.15 | ✅ |
| Gamma Bank | 92% | 150ms | ₹0.20 | ✅ |
| Delta Bank | 85% | 300ms | ₹0.10 | ✅ |

**These are the banks SmartRoutingService chooses from.** Alpha has the highest success rate, so it gets most traffic (90% exploit). The other 3 share the exploration traffic (10%).

```java
        private void addBank(BankRoute route) {
            bankRoutes.put(route.getBankId(), route);
            successCounts.put(route.getBankId(), new AtomicLong(0));
            totalCounts.put(route.getBankId(), new AtomicLong(0));
            totalLatency.put(route.getBankId(), new AtomicLong(0));
        }

        @Override
        public List<BankRoute> getAllBankRoutes() {
            return new ArrayList<>(bankRoutes.values());
        }

        @Override
        public List<BankRoute> getActiveBankRoutes() {
            return bankRoutes.values().stream()
                    .filter(BankRoute::isActive)
                    .toList();
        }

        @Override
        public BankRoute getBankRoute(String bankId) {
            return bankRoutes.get(bankId);
        }

        @Override
        public void recordTransactionResult(String bankId, boolean success, long latencyMs) {
            totalCounts.computeIfAbsent(bankId, k -> new AtomicLong(0)).incrementAndGet();
            totalLatency.computeIfAbsent(bankId, k -> new AtomicLong(0)).addAndGet(latencyMs);

            if (success) {
                successCounts.computeIfAbsent(bankId, k -> new AtomicLong(0)).incrementAndGet();
            }

            // Update running averages
            BankRoute route = bankRoutes.get(bankId);
            if (route != null) {
                long total = totalCounts.get(bankId).get();
                long successes = successCounts.get(bankId).get();
                long totalLat = totalLatency.get(bankId).get();

                route.setSuccessRate((double) successes / total);
                route.setAvgLatencyMs((double) totalLat / total);
            }
        }
```

**`recordTransactionResult()` — THE LEARNING MECHANISM:**

```
Before: Alpha Bank successRate=0.95 (initial), avgLatency=120ms
Transaction: success=true, latency=100ms

totalCounts["bank-alpha"] → 1 (from 0)
successCounts["bank-alpha"] → 1 (from 0)
totalLatency["bank-alpha"] → 100 (from 0)

newSuccessRate = 1 / 1 = 1.0 (100%)
newAvgLatency = 100 / 1 = 100ms

After: Alpha Bank successRate=1.0, avgLatency=100ms
```

**NOTE:** The initial success rates (0.95, 0.88, etc.) are OVERWRITTEN on the first transaction because the counts start at 0. In production, you'd initialize counters to match the initial rates.

```java
        @Override
        public void updateBankRoute(BankRoute bankRoute) {
            bankRoutes.put(bankRoute.getBankId(), bankRoute);
        }

        @Override
        public long getTransactionCount(String bankId) {
            AtomicLong count = totalCounts.get(bankId);
            return count != null ? count.get() : 0;
        }
    }
}
```

---

## 4. Step-by-Step: Resilience4jConfig.java

**File:** `src/main/java/com/payflow/routing/config/Resilience4jConfig.java`

Creates a programmatic `CircuitBreakerRegistry` with event listeners for monitoring.

### Full Source Code

```java
package com.payflow.routing.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
```

```java
/**
 * Resilience4j Circuit Breaker configuration for bank communication.
 * <p>
 * The circuit breaker protects against cascading failures when the bank
 * simulator is unavailable or responding slowly.
 */
@Configuration
public class Resilience4jConfig {

    private static final Logger log = LoggerFactory.getLogger(Resilience4jConfig.class);

    @Value("${resilience4j.circuitbreaker.failure-rate-threshold:50}")
    private float failureRateThreshold;

    @Value("${resilience4j.circuitbreaker.slow-call-rate-threshold:80}")
    private float slowCallRateThreshold;

    @Value("${resilience4j.circuitbreaker.slow-call-duration-threshold-seconds:5}")
    private int slowCallDurationThresholdSeconds;

    @Value("${resilience4j.circuitbreaker.sliding-window-size:10}")
    private int slidingWindowSize;

    @Value("${resilience4j.circuitbreaker.minimum-number-of-calls:5}")
    private int minimumNumberOfCalls;

    @Value("${resilience4j.circuitbreaker.wait-duration-in-open-state-seconds:30}")
    private int waitDurationInOpenStateSeconds;

    @Value("${resilience4j.circuitbreaker.permitted-calls-in-half-open:3}")
    private int permittedCallsInHalfOpen;
```

**7 CONFIGURABLE PROPERTIES** — all from `application.yml` (explained in Part 9a):

| Property | Value | Meaning |
|---|---|---|
| `failureRateThreshold` | 50% | Open circuit if 50%+ of calls fail |
| `slowCallRateThreshold` | 80% | Open circuit if 80%+ of calls are slow |
| `slowCallDurationThresholdSeconds` | 5s | A call is "slow" if it takes > 5 seconds |
| `slidingWindowSize` | 10 | Evaluate the last 10 calls |
| `minimumNumberOfCalls` | 5 | Don't evaluate until 5+ calls made |
| `waitDurationInOpenStateSeconds` | 30s | Stay open for 30s before trying half-open |
| `permittedCallsInHalfOpen` | 3 | Allow 3 test calls in half-open state |

### The CircuitBreakerRegistry Bean

```java
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slowCallRateThreshold(slowCallRateThreshold)
                .slowCallDurationThreshold(Duration.ofSeconds(slowCallDurationThresholdSeconds))
                .slidingWindowSize(slidingWindowSize)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(Duration.ofSeconds(waitDurationInOpenStateSeconds))
                .permittedNumberOfCallsInHalfOpenState(permittedCallsInHalfOpen)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
```

**PROGRAMMATIC CONFIG** — same settings as the YAML `instances.bankCommunication` block, but created in Java code. This gives us the ability to add event listeners.

**`COUNT_BASED` sliding window** — evaluates the last N calls (not the last N seconds). Simpler and more predictable.

**`automaticTransitionFromOpenToHalfOpenEnabled(true)`** — after 30 seconds in OPEN state, automatically transition to HALF_OPEN (don't wait for a request to trigger the transition).

### Event Listeners — Monitoring

```java
        // Register event listeners for monitoring
        CircuitBreaker breaker = registry.circuitBreaker("bankCommunication");
        breaker.getEventPublisher()
                .onStateTransition(event ->
                        log.warn("Circuit Breaker '{}' state transition: {} -> {}",
                                event.getCircuitBreakerName(),
                                event.getStateTransition().getFromState(),
                                event.getStateTransition().getToState()))
                .onError(event ->
                        log.debug("Circuit Breaker '{}' error: {}",
                                event.getCircuitBreakerName(),
                                event.getThrowable().getMessage()));

        return registry;
    }
}
```

**TWO EVENT LISTENERS:**

| Event | Log Level | What It Logs |
|---|---|---|
| `onStateTransition` | WARN | "Circuit Breaker 'bankCommunication' state transition: CLOSED -> OPEN" |
| `onError` | DEBUG | "Circuit Breaker 'bankCommunication' error: Connection refused" |

**WHY WARN for transitions?** State transitions are significant operational events — you want to know when the circuit opens (bank is failing) and when it closes (bank recovered).

**WHY DEBUG for errors?** Individual errors are expected during normal operation (occasional timeouts). Only aggregated failures (captured by the threshold) warrant warnings.

**`"bankCommunication"`** — this is the circuit breaker instance name. The `@CircuitBreaker(name = "bankCommunication")` annotation on the controller references this same name.

---

## 5. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **DynamoDbClient.builder()** | AWS SDK v2 builder pattern — endpointOverride + region + credentials |
| 2 | **LocalStack** | Emulates AWS services locally — DynamoDB at localhost:4566 |
| 3 | **StaticCredentialsProvider** | Hardcoded credentials for dev. Production uses DefaultCredentialsProvider (IAM) |
| 4 | **`@Profile({"dev", "default", "docker"})`** | Conditional bean — only created in non-production profiles |
| 5 | **InMemoryRoutingMetricsRepository** | ConcurrentHashMap-based implementation with 4 pre-configured banks |
| 6 | **AtomicLong** | Thread-safe counter — `incrementAndGet()` and `addAndGet()` without synchronized |
| 7 | **Running average calculation** | `successRate = successes / total`, `avgLatency = totalLatency / total` |
| 8 | **Nested static class** | `InMemoryRoutingMetricsRepository` lives inside `DynamoDbConfig` — keeps related code together |
| 9 | **CircuitBreakerConfig.custom()** | Programmatic circuit breaker config with builder pattern |
| 10 | **COUNT_BASED sliding window** | Evaluates last N calls (not last N seconds) |
| 11 | **automaticTransitionFromOpenToHalfOpenEnabled** | Auto-transition after wait duration (no request trigger needed) |
| 12 | **Event listeners** | `onStateTransition` (WARN) + `onError` (DEBUG) for monitoring |
| 13 | **Circuit breaker instance name** | `"bankCommunication"` — links config to `@CircuitBreaker` annotation |
| 14 | **DynamoDbClient bean unused in dev** | Created for production readiness — in-memory repo is used in dev |

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
| [Part 9h](./phase4-part09h-smart-routing.md) | Smart Routing + Tests |
| **Part 9i** | **Config Classes** (You are here) |
| [Part 9j](./phase4-part09j-controller-docker.md) | Controller + DTOs + Docker + curl |
| [Part 9k](./phase4-part09k-connections-flows.md) | How Everything Connects |

---

*Next: [Part 9j — Controller + DTOs + Docker + curl](./phase4-part09j-controller-docker.md) →*
