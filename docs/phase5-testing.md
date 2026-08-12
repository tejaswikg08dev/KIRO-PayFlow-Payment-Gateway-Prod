# Phase 5: Testing Strategy Overview

## Overview

Comprehensive testing strategy for PayFlow Payment Gateway covering unit tests, integration tests, controller tests, and end-to-end flows. Follows the testing pyramid with emphasis on fast, reliable unit tests at the base.

## Testing Pyramid

```
          /\
         /  \
        / E2E \          ← Few, slow, expensive
       /  Tests \          (Selenium, full stack)
      /──────────\
     / Integration \     ← Moderate count
    /    Tests      \      (Testcontainers, DB)
   /────────────────\
  /  Controller Tests \  ← Many
 /    (@WebMvcTest)    \   (MockMvc, fast)
/──────────────────────\
/      Unit Tests        \ ← Most, fastest
/ (Mockito, no Spring ctx) \  (Milliseconds)
/────────────────────────────\
```

## Tools & Dependencies

| Tool | Purpose | Scope |
|------|---------|-------|
| JUnit 5 | Test framework | All tests |
| Mockito | Mocking framework | Unit tests |
| AssertJ | Fluent assertions | All tests |
| MockMvc | Controller testing | Controller tests |
| Testcontainers | Real DB/Kafka in Docker | Integration tests |
| REST Assured | API testing DSL | Integration tests |
| WireMock | External service mocking | Integration tests |
| Awaitility | Async testing | Kafka consumer tests |
| H2 Database | In-memory DB | Quick unit/controller tests |
| Jacoco | Code coverage | Coverage reports |

## Test Dependencies (pom.xml)

```xml
<dependencies>
    <!-- JUnit 5 + Mockito (from spring-boot-starter-test) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- Testcontainers -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>postgresql</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>kafka</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- REST Assured -->
    <dependency>
        <groupId>io.rest-assured</groupId>
        <artifactId>rest-assured</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- WireMock -->
    <dependency>
        <groupId>org.wiremock</groupId>
        <artifactId>wiremock-standalone</artifactId>
        <version>3.3.1</version>
        <scope>test</scope>
    </dependency>

    <!-- Awaitility for async -->
    <dependency>
        <groupId>org.awaitility</groupId>
        <artifactId>awaitility</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- H2 for quick tests -->
    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

## Test Coverage Targets

| Layer | Target Coverage | Priority |
|-------|----------------|----------|
| Service Layer | 90%+ | Critical business logic |
| Controller Layer | 85%+ | Input validation, routing |
| Repository Layer | 70%+ | Custom queries only |
| Configuration | 60%+ | Security configs |
| Domain/Entity | 80%+ | State machines, validation |

## Test Naming Convention

```java
// Pattern: methodName_whenCondition_shouldExpectedBehavior
@Test
void createOrder_whenValidRequest_shouldReturnCreatedOrder() {}

@Test
void createOrder_whenDuplicateIdempotencyKey_shouldReturnCachedResponse() {}

@Test
void processPayment_whenBankDeclines_shouldSetStatusFailed() {}

@Test
void capturePayment_whenOrderNotAuthorized_shouldThrow409() {}
```

## Test Categories

```java
// Tagging for selective test execution
@Tag("unit")        // Fast, no external dependencies
@Tag("integration") // Needs Docker (Testcontainers)
@Tag("e2e")         // Full stack required
@Tag("slow")        // Takes > 5 seconds

// Maven profiles
mvn test                          # Unit tests only
mvn test -Pintegration            # Integration tests
mvn verify                        # All tests
```

## CI/CD Test Strategy

```yaml
# GitHub Actions test stages
jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - run: mvn test -pl payment-service  # Fast (~30s)

  integration-tests:
    runs-on: ubuntu-latest
    needs: unit-tests
    steps:
      - run: mvn verify -Pintegration      # Slower (~3min)

  e2e-tests:
    runs-on: ubuntu-latest
    needs: integration-tests
    steps:
      - run: docker compose up -d
      - run: mvn verify -Pe2e              # Slowest (~5min)
```

## What to Test per Service

| Service | Key Test Scenarios |
|---------|-------------------|
| Identity | Register, login, token refresh, duplicate email, expired token |
| Merchant | Onboarding, API key generation, key validation, revocation |
| Payment | Order creation, state transitions, idempotency, capture, refund |
| Routing | Fraud detection, ISO 8583 encoding/decoding, circuit breaker |
| Settlement | Fee calculation, batch processing, payout creation |
| Webhook | Event consumption, HMAC signing, retry logic |
| Notification | Template rendering, email/SMS sending |
