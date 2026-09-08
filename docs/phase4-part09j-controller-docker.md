# 🏗️ Phase 4 Part 9j: Routing Service — Controller + DTOs + Docker + curl

> **"One endpoint. Six steps. Fraud check → route selection → ISO 8583 build → Netty TCP send → parse response → record metrics. The controller orchestrates them all."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 9j — Controller + DTOs + Docker + curl |
| **What You Build** | RoutingRequest.java, RoutingResponse.java, RoutingController.java, Dockerfile |
| **Previous** | [Part 9i — Config Classes](./phase4-part09i-config-classes.md) |
| **Next** | [Part 9k — Connections & Flows](./phase4-part09k-connections-flows.md) |

---

## 📖 Table of Contents

1. [Overview — 4 Files in This Part](#1-overview--4-files-in-this-part)
2. [Folder Structure After This Part](#2-folder-structure-after-this-part)
3. [Step-by-Step: RoutingRequest.java](#3-step-by-step-routingrequestjava)
4. [Step-by-Step: RoutingResponse.java](#4-step-by-step-routingresponsejava)
5. [Step-by-Step: RoutingController.java](#5-step-by-step-routingcontrollerjava)
6. [Step-by-Step: Dockerfile](#6-step-by-step-dockerfile)
7. [Testing with curl](#7-testing-with-curl)
8. [What You Learned](#8-what-you-learned)

---

## 1. Overview — 4 Files in This Part

| File | Type | What's Different from Previous Controllers |
|---|---|---|
| `RoutingRequest` | Input DTO | Plain Java (no Lombok), manual getters/setters, 6-arg constructor |
| `RoutingResponse` | Output DTO | Static factory methods: `approved()`, `declined()`, `fraudDeclined()`, `error()` |
| `RoutingController` | Controller | **6-step orchestration**, `@CircuitBreaker`, builds ISO 8583, calls Netty TCP |
| `Dockerfile` | Docker | Port 8084, multi-stage build |

**THIS CONTROLLER IS UNLIKE ANY PREVIOUS CONTROLLER.** Previous controllers called services that talked to databases. This controller orchestrates: fraud detection → bank selection → binary protocol construction → TCP networking → response parsing → metrics recording.

---

## 2. Folder Structure After This Part

```
backend/routing-service/src/main/java/com/payflow/routing/
├── dto/
│   ├── RoutingRequest.java           ← YOU CREATE THIS
│   └── RoutingResponse.java          ← YOU CREATE THIS
└── controller/
    └── RoutingController.java        ← YOU CREATE THIS

backend/routing-service/
└── Dockerfile                         ← YOU CREATE THIS
```

---

## 3. Step-by-Step: RoutingRequest.java

**File:** `src/main/java/com/payflow/routing/dto/RoutingRequest.java`

### Full Source Code

```java
package com.payflow.routing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Request DTO for routing a payment transaction to a bank.
 */
public class RoutingRequest {

    @NotBlank(message = "Merchant ID is required")
    private String merchantId;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    private BigDecimal amount;

    @NotBlank(message = "Currency is required")
    private String currency;

    @NotBlank(message = "Payment method is required")
    private String paymentMethod;

    private String cardBin;

    private String cardNumber; // Masked PAN (e.g., "411111******1111")

    public RoutingRequest() {
    }

    public RoutingRequest(String merchantId, BigDecimal amount, String currency,
                          String paymentMethod, String cardBin, String cardNumber) {
        this.merchantId = merchantId;
        this.amount = amount;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
        this.cardBin = cardBin;
        this.cardNumber = cardNumber;
    }

    // ... standard getters and setters for all 6 fields ...
}
```

**PLAIN JAVA — no Lombok, no record:**

| Approach | Previous Services Used | Routing Service Uses | Why |
|---|---|---|---|
| `@Data` (Lombok) | ✅ Payment DTOs | ❌ | Routing service uses minimal dependencies |
| Record | ❌ | ❌ | Needs no-arg constructor for Jackson deserialization |
| Manual class | ❌ | ✅ | Full control, no dependencies |

**6 FIELDS:**

| Field | Validation | Used By | Example |
|---|---|---|---|
| `merchantId` | `@NotBlank` | Fraud velocity check | `"merchant-001"` |
| `amount` | `@NotNull @DecimalMin("0.01")` | Fraud amount check, ISO 8583 field 4 | `1500.00` |
| `currency` | `@NotBlank` | Fraud geo-check, ISO 8583 field 49 | `"INR"` |
| `paymentMethod` | `@NotBlank` | Fraud method risk | `"CARD"` |
| `cardBin` | Optional | Fraud BIN risk | `"411111"` |
| `cardNumber` | Optional | ISO 8583 field 2 (PAN) | `"4111111111111111"` |

**WHO SENDS THIS?** Payment Service's `RoutingServiceClient` (Feign client) sends this JSON to `POST /internal/route`.

---

## 4. Step-by-Step: RoutingResponse.java

**File:** `src/main/java/com/payflow/routing/dto/RoutingResponse.java`

### Full Source Code

```java
package com.payflow.routing.dto;

/**
 * Response DTO returned after routing a payment transaction.
 */
public class RoutingResponse {

    private boolean success;
    private String authorizationCode;
    private String responseCode;
    private String responseMessage;
    private String bankId;
    private long latencyMs;

    public RoutingResponse() {
    }

    public RoutingResponse(boolean success, String authorizationCode, String responseCode,
                           String responseMessage, String bankId, long latencyMs) {
        this.success = success;
        this.authorizationCode = authorizationCode;
        this.responseCode = responseCode;
        this.responseMessage = responseMessage;
        this.bankId = bankId;
        this.latencyMs = latencyMs;
    }

    // Static factory methods

    public static RoutingResponse approved(String authorizationCode, String bankId, long latencyMs) {
        return new RoutingResponse(true, authorizationCode, "00", "Approved", bankId, latencyMs);
    }

    public static RoutingResponse declined(String responseCode, String message, String bankId, long latencyMs) {
        return new RoutingResponse(false, null, responseCode, message, bankId, latencyMs);
    }

    public static RoutingResponse fraudDeclined(String reason) {
        return new RoutingResponse(false, null, "59", "Suspected Fraud: " + reason, null, 0);
    }

    public static RoutingResponse error(String message) {
        return new RoutingResponse(false, null, "96", "System Error: " + message, null, 0);
    }

    // ... standard getters and setters for all 6 fields ...
}
```

**6 FIELDS:**

| Field | Type | In Approved | In Declined | In Fraud Declined |
|---|---|---|---|---|
| `success` | boolean | `true` | `false` | `false` |
| `authorizationCode` | String | `"A12345"` | `null` | `null` |
| `responseCode` | String | `"00"` | `"51"` | `"59"` |
| `responseMessage` | String | `"Approved"` | `"Insufficient Funds"` | `"Suspected Fraud: ..."` |
| `bankId` | String | `"bank-alpha"` | `"bank-alpha"` | `null` (never reached bank) |
| `latencyMs` | long | `1200` | `800` | `0` (no bank call) |

**4 STATIC FACTORY METHODS — cleaner than constructors:**

```java
// Instead of:
new RoutingResponse(true, "A12345", "00", "Approved", "bank-alpha", 1200)

// Use:
RoutingResponse.approved("A12345", "bank-alpha", 1200)
RoutingResponse.declined("51", "Insufficient Funds", "bank-alpha", 800)
RoutingResponse.fraudDeclined("Velocity exceeded")
RoutingResponse.error("Bank communication unavailable")
```

---

## 5. Step-by-Step: RoutingController.java

**File:** `src/main/java/com/payflow/routing/controller/RoutingController.java`

**THE MOST COMPLEX CONTROLLER IN PAYFLOW.** It orchestrates 6 steps and touches every technology domain.

### Imports and Class Declaration

```java
package com.payflow.routing.controller;

import com.payflow.routing.dto.RoutingRequest;
import com.payflow.routing.dto.RoutingResponse;
import com.payflow.routing.fraud.FraudResult;
import com.payflow.routing.iso8583.*;
import com.payflow.routing.netty.BankNettyClient;
import com.payflow.routing.routing.BankRoute;
import com.payflow.routing.routing.RoutingDecision;
import com.payflow.routing.service.FraudDetectionService;
import com.payflow.routing.service.SmartRoutingService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
```

**IMPORTS FROM EVERY DOMAIN we built:**
- `fraud` — FraudResult
- `iso8583` — Iso8583Message, Builder, Constants
- `netty` — BankNettyClient
- `routing` — BankRoute, RoutingDecision
- `service` — FraudDetectionService, SmartRoutingService
- `resilience4j` — @CircuitBreaker

```java
/**
 * Internal routing controller called by payment-service to route transactions to banks.
 */
@RestController
@RequestMapping("/internal")
@Tag(name = "Routing", description = "Internal transaction routing API")
public class RoutingController {

    private static final Logger log = LoggerFactory.getLogger(RoutingController.class);

    private final SmartRoutingService smartRoutingService;
    private final FraudDetectionService fraudDetectionService;
    private final BankNettyClient bankNettyClient;

    public RoutingController(SmartRoutingService smartRoutingService,
                             FraudDetectionService fraudDetectionService,
                             BankNettyClient bankNettyClient) {
        this.smartRoutingService = smartRoutingService;
        this.fraudDetectionService = fraudDetectionService;
        this.bankNettyClient = bankNettyClient;
    }
```

**3 DEPENDENCIES:**

| Dependency | Domain | Purpose |
|---|---|---|
| `FraudDetectionService` | Fraud (9f/9g) | Step 1: Check if transaction is fraudulent |
| `SmartRoutingService` | Routing (9h) | Step 2: Pick the best bank |
| `BankNettyClient` | Netty (9d) | Steps 3-5: Send ISO 8583 to bank, receive response |

**`@RequestMapping("/internal")`** — ALL endpoints are under `/internal`. This is NOT exposed through the API Gateway. Only internal Feign calls reach this path.

### routeTransaction() — The 6-Step Orchestration

```java
    /**
     * Routes a payment transaction to the appropriate bank.
     * Performs fraud detection, selects optimal route, and communicates with the bank via ISO 8583.
     */
    @PostMapping("/route")
    @Operation(summary = "Route a payment transaction", description = "Called by payment-service to route transactions")
    @CircuitBreaker(name = "bankCommunication", fallbackMethod = "routeFallback")
    public ResponseEntity<RoutingResponse> routeTransaction(@Valid @RequestBody RoutingRequest request) {
        long startTime = System.currentTimeMillis();
```

**`@CircuitBreaker(name = "bankCommunication", fallbackMethod = "routeFallback")`** — 🆕 **NEW ANNOTATION**

If the method throws exceptions repeatedly (50% failure rate in last 10 calls), the circuit breaker OPENS and calls `routeFallback()` instead — returning an error immediately without attempting the bank call.

**`System.currentTimeMillis()`** — records the start time to calculate total latency.

```java
        log.info("Routing transaction: merchant={}, amount={}, currency={}",
                request.getMerchantId(), request.getAmount(), request.getCurrency());

        // Step 1: Fraud detection
        FraudResult fraudResult = fraudDetectionService.analyze(request);
        if (fraudResult.isDeclined()) {
            log.warn("Transaction DECLINED by fraud detection: score={}, reasons={}",
                    fraudResult.score(), fraudResult.reasons());
            return ResponseEntity.ok(RoutingResponse.fraudDeclined(
                    String.join("; ", fraudResult.reasons())));
        }
```

**STEP 1: FRAUD CHECK.** If score > 70 → DECLINE. Return response code "59" (Suspected Fraud) immediately. The bank is NEVER called — saving processing fees.

**`String.join("; ", fraudResult.reasons())`** — joins all fraud reasons into one string: `"Velocity exceeded; High amount; Night transaction"`.

```java
        // Step 2: Select route using epsilon-greedy
        RoutingDecision decision = smartRoutingService.selectRoute();
        BankRoute selectedBank = decision.selectedBank();

        log.info("Route selected: bank={}, explore={}", selectedBank.getBankName(), decision.isExplore());
```

**STEP 2: SMART ROUTING.** Epsilon-greedy selects a bank. 90% exploit (best), 10% explore (random).

```java
        // Step 3: Build ISO 8583 message
        Iso8583Message isoRequest = buildIso8583Request(request);
```

**STEP 3: BUILD BINARY MESSAGE.** Converts JSON request into ISO 8583 binary format.

```java
        // Step 4: Send to bank via Netty
        try {
            CompletableFuture<Iso8583Message> responseFuture = bankNettyClient.sendMessage(isoRequest);
            Iso8583Message isoResponse = responseFuture.join();

            long latencyMs = System.currentTimeMillis() - startTime;
```

**STEP 4: SEND VIA TCP.** `sendMessage()` returns a `CompletableFuture`. `.join()` blocks until the bank responds (or timeout).

**`responseFuture.join()`** — blocks the calling thread. In a fully reactive system, you'd use `.thenApply()` for non-blocking. Here, Spring MVC's thread-per-request model is fine.

```java
            // Step 5: Parse response
            String responseCode = isoResponse.getField(Iso8583Constants.FIELD_RESPONSE_CODE);
            String authCode = isoResponse.getField(Iso8583Constants.FIELD_AUTH_CODE);
            boolean success = Iso8583Constants.RESPONSE_APPROVED.equals(responseCode);
```

**STEP 5: PARSE BANK RESPONSE.** Extract response code (field 39) and auth code (field 38). If code is "00" → approved.

```java
            // Step 6: Record metrics for future routing
            smartRoutingService.recordResult(selectedBank.getBankId(), success, latencyMs);
```

**STEP 6: RECORD METRICS.** Update the bank's success rate and latency. This is how the epsilon-greedy algorithm LEARNS.

```java
            if (success) {
                log.info("Transaction APPROVED: bank={}, authCode={}, latency={}ms",
                        selectedBank.getBankName(), authCode, latencyMs);
                return ResponseEntity.ok(RoutingResponse.approved(authCode, selectedBank.getBankId(), latencyMs));
            } else {
                String message = mapResponseCodeToMessage(responseCode);
                log.info("Transaction DECLINED by bank: code={}, message={}", responseCode, message);
                return ResponseEntity.ok(RoutingResponse.declined(responseCode, message, selectedBank.getBankId(), latencyMs));
            }

        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            smartRoutingService.recordResult(selectedBank.getBankId(), false, latencyMs);
            log.error("Bank communication failed: {}", e.getMessage(), e);
            throw new RuntimeException("Bank communication error", e);
        }
    }
```

**ERROR HANDLING:** If Netty/TCP fails, record the failure (bank gets lower success rate) and re-throw. The `@CircuitBreaker` annotation catches this and may open the circuit.

### routeFallback — Circuit Breaker Open

```java
    /**
     * Fallback method when the circuit breaker is open.
     */
    public ResponseEntity<RoutingResponse> routeFallback(RoutingRequest request, Throwable throwable) {
        log.error("Circuit breaker OPEN - bank communication unavailable: {}", throwable.getMessage());
        return ResponseEntity.ok(RoutingResponse.error(
                "Bank communication temporarily unavailable. Please retry."));
    }
```

**CALLED WHEN CIRCUIT IS OPEN:** Instead of attempting the bank call (which would fail), return an error immediately. Response code "96" (System Error).

**`Throwable throwable`** — the exception that caused the circuit to open. Logged for debugging.

### buildIso8583Request — JSON → Binary

```java
    /**
     * Builds an ISO 8583 authorization request from the routing request.
     */
    private Iso8583Message buildIso8583Request(RoutingRequest request) {
        String traceNumber = String.format("%06d", System.nanoTime() % 1000000);
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
        String amountStr = String.format("%012d", request.getAmount().movePointRight(2).longValue());

        return new Iso8583MessageBuilder()
                .setMti(Iso8583Constants.MTI_AUTH_REQUEST)
                .setPan(request.getCardNumber() != null ? request.getCardNumber() : "0000000000000000")
                .setProcessingCode(Iso8583Constants.PROC_CODE_PURCHASE)
                .setAmount(amountStr)
                .setTraceNumber(traceNumber)
                .setTime(time)
                .setCurrencyCode(request.getCurrency() != null ? mapCurrencyToCode(request.getCurrency()) : "840")
                .build();
    }
```

**TRANSLATES JSON → ISO 8583 fields:**

| JSON Field | ISO 8583 Field | Conversion |
|---|---|---|
| `cardNumber` | Field 2 (PAN) | Direct (or default `"0000000000000000"`) |
| — | Field 3 (Processing Code) | Always `"000000"` (purchase) |
| `amount` | Field 4 (Amount) | `BigDecimal.movePointRight(2)` → paise, then `%012d` zero-pad to 12 digits |
| — | Field 11 (Trace) | `System.nanoTime() % 1000000` → 6-digit unique trace |
| — | Field 12 (Time) | `LocalTime.now()` formatted as `"HHmmss"` |
| `currency` | Field 49 (Currency Code) | Map name to ISO numeric (`"INR"` → `"356"`) |

**`request.getAmount().movePointRight(2).longValue()`** — converts ₹1,500.00 → 150000 (paise). ISO 8583 amounts are always in minor currency units.

**`String.format("%012d", 150000)`** → `"000000150000"` — zero-padded to 12 digits.

### Helper Methods

```java
    /**
     * Maps ISO currency name to numeric code.
     */
    private String mapCurrencyToCode(String currency) {
        return switch (currency.toUpperCase()) {
            case "USD" -> "840";
            case "EUR" -> "978";
            case "GBP" -> "826";
            case "JPY" -> "392";
            case "INR" -> "356";
            default -> "840";
        };
    }

    /**
     * Maps ISO 8583 response codes to human-readable messages.
     */
    private String mapResponseCodeToMessage(String responseCode) {
        if (responseCode == null) return "Unknown error";
        return switch (responseCode) {
            case "00" -> "Approved";
            case "05" -> "Do Not Honor";
            case "14" -> "Invalid Card Number";
            case "51" -> "Insufficient Funds";
            case "54" -> "Expired Card";
            case "59" -> "Suspected Fraud";
            case "96" -> "System Malfunction";
            default -> "Declined (code: " + responseCode + ")";
        };
    }
}
```

---

## 6. Step-by-Step: Dockerfile

**File:** `backend/routing-service/Dockerfile`

```dockerfile
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Copy parent pom and all modules (Maven reactor requires all to be present)
COPY pom.xml .
COPY common-lib ./common-lib
COPY service-registry ./service-registry
COPY config-server ./config-server
COPY api-gateway ./api-gateway
COPY identity-service ./identity-service
COPY merchant-service ./merchant-service
COPY payment-service ./payment-service
COPY routing-service ./routing-service
COPY settlement-service ./settlement-service
COPY webhook-service ./webhook-service
COPY notification-service ./notification-service
COPY bank-simulator ./bank-simulator

# Build only the target service and its dependencies
RUN mvn clean package -pl routing-service -am -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S payflow && adduser -S payflow -G payflow
USER payflow
COPY --from=builder /app/routing-service/target/*.jar app.jar
EXPOSE 8084
HEALTHCHECK --interval=15s --timeout=10s --retries=5 --start-period=30s \
    CMD wget -qO- http://localhost:8084/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**MULTI-STAGE BUILD — same pattern as previous services:**

| Stage | Base Image | Purpose |
|---|---|---|
| **Builder** | `maven:3.9-eclipse-temurin-17` | Compile and package (Maven + JDK) |
| **Runtime** | `eclipse-temurin:17-jre-alpine` | Run the JAR (JRE only, ~100MB smaller) |

**PORT 8084** — not 8081/8082/8083. Each service gets its own port.

**`-pl routing-service -am`** — Maven: build only routing-service and its dependencies (common-lib).

**HEALTHCHECK** — Docker pings `/actuator/health` every 15 seconds to verify the service is alive.

---

## 7. Testing with curl

**Prerequisites:** routing-service running on port 8084, bank-simulator on port 9090 (optional — without it, Netty connection fails gracefully).

```powershell
# 1. Health Check
curl http://localhost:8084/actuator/health

# Expected: {"status":"UP","components":{"circuitBreakers":{"status":"UP",...}}}

# 2. Route a normal transaction (₹1,500 INR, Visa card)
curl -X POST http://localhost:8084/internal/route `
  -H "Content-Type: application/json" `
  -d '{\"merchantId\":\"merchant-001\",\"amount\":1500.00,\"currency\":\"INR\",\"paymentMethod\":\"CARD\",\"cardBin\":\"411111\",\"cardNumber\":\"4111111111111111\"}'

# Expected (with bank simulator): {"success":true,"authorizationCode":"A12345","responseCode":"00",...}
# Expected (without bank simulator): error (connection refused → circuit may open)

# 3. Route a high-amount transaction (₹5,00,000 — triggers fraud amount check)
curl -X POST http://localhost:8084/internal/route `
  -H "Content-Type: application/json" `
  -d '{\"merchantId\":\"merchant-001\",\"amount\":500000.00,\"currency\":\"INR\",\"paymentMethod\":\"PREPAID\",\"cardBin\":\"600000\",\"cardNumber\":\"6000000000000000\"}'

# Expected: Higher fraud score (high amount + prepaid + Discover BIN)

# 4. Route a sanctioned-country transaction (North Korean Won)
curl -X POST http://localhost:8084/internal/route `
  -H "Content-Type: application/json" `
  -d '{\"merchantId\":\"merchant-001\",\"amount\":1000.00,\"currency\":\"KPW\",\"paymentMethod\":\"CARD\",\"cardBin\":\"411111\"}'

# Expected: {"success":false,"responseCode":"59","responseMessage":"Suspected Fraud: ..."}

# 5. Check circuit breaker status
curl http://localhost:8084/actuator/circuitbreakers

# 6. Check Swagger UI
# Open: http://localhost:8084/swagger-ui.html
```

---

## 8. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **Plain Java DTOs** | No Lombok, no record — full manual getters/setters, explicit constructors |
| 2 | **Static factory methods** | `RoutingResponse.approved()`, `.declined()`, `.fraudDeclined()`, `.error()` — cleaner than constructors |
| 3 | **6-step orchestration** | Fraud → Route → Build ISO 8583 → Send TCP → Parse → Record metrics |
| 4 | **`@CircuitBreaker` annotation** | Resilience4j wraps the method — opens circuit on repeated failures, calls fallback |
| 5 | **Fallback method** | `routeFallback(request, throwable)` — returns error response when circuit is open |
| 6 | **`@RequestMapping("/internal")`** | All endpoints under `/internal` — not exposed through API Gateway |
| 7 | **`CompletableFuture.join()`** | Blocks until Netty response arrives — bridges async TCP to sync controller |
| 8 | **BigDecimal.movePointRight(2)** | ₹1,500.00 → 150000 paise — ISO 8583 amounts in minor units |
| 9 | **`String.format("%012d", ...)`** | Zero-pad amount to 12 digits for ISO 8583 field 4 |
| 10 | **`System.nanoTime() % 1000000`** | Generate 6-digit trace number for ISO 8583 field 11 |
| 11 | **Currency code mapping** | `"INR"` → `"356"`, `"USD"` → `"840"` — ISO 4217 numeric codes |
| 12 | **Response code mapping** | `"00"` → "Approved", `"51"` → "Insufficient Funds" — human-readable messages |
| 13 | **Fraud decline = no bank call** | Response code "59" returned immediately — saves bank processing fee |
| 14 | **recordResult after every txn** | Both success and failure recorded — epsilon-greedy algorithm learns from both |
| 15 | **Multi-stage Docker build** | Builder stage (Maven+JDK) → Runtime stage (JRE only, smaller image) |
| 16 | **EXPOSE 8084** | Routing service port — different from previous services (8081-8083) |

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
| [Part 9i](./phase4-part09i-config-classes.md) | Config Classes |
| **Part 9j** | **Controller + DTOs + Docker + curl** (You are here) |
| [Part 9k](./phase4-part09k-connections-flows.md) | How Everything Connects |

---

*Next: [Part 9k — How Everything Connects](./phase4-part09k-connections-flows.md) →*
