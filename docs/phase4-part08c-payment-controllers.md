# Phase 4 Part 8c: Payment Service — Controllers, Feign Clients & Tests

| Field | Details |
|-------|---------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Hands-On Coding |
| **Part** | 8c of 16 (Payment Service — API Layer) |
| **Previous** | [Phase 4 Part 8b: Payment Services & Kafka](./phase4-part08b-payment-services-kafka.md) |
| **Next** | [Phase 4 Part 9a: Routing Service — ISO 8583](./phase4-part09a-routing-iso8583.md) |
| **Time to Complete** | 2-3 hours |
| **Difficulty** | Intermediate |
| **Prerequisites** | Part 8b completed, payment-service compiles |
| **What You'll Build** | REST controllers, Feign clients for inter-service calls, exception handler, tests |
| **Git Commit** | "Phase 4 Part 8c: Add payment controllers, Feign clients, and tests" |

---

## Table of Contents
- [Step 1: Feign Clients (Inter-Service Communication)](#feign-clients)
- [Step 2: OrderController](#order-controller)
- [Step 3: PaymentController (with Idempotency)](#payment-controller)
- [Step 4: RefundController](#refund-controller)
- [Step 5: PaymentExceptionHandler](#exception-handler)
- [Step 6: FeignConfig](#feign-config)
- [Step 7: SecurityConfig](#security-config)
- [Step 8: Unit Tests](#tests)
- [Verification — Full Payment Flow](#verification)
- [What You Learned](#what-you-learned)
- [Common Errors & Fixes](#common-errors)
- [Git Commit](#git-commit)

---

## What You'll Learn in This Part
- How Feign clients call other microservices (declarative HTTP)
- How to design REST controllers with proper status codes
- How idempotency integrates into the controller layer
- How to write unit tests with Mockito for payment logic
- How to write controller tests with MockMvc

---

<a name="feign-clients"></a>
## Step 1: Feign Clients — Calling Other Microservices

**What is Feign?** A declarative HTTP client. You write a Java interface, annotate it with `@FeignClient`, and Spring generates the HTTP client code at runtime. No `RestTemplate` or `WebClient` boilerplate.

**Think of it like:** "I declare what I want to call. Spring figures out how to call it."

### RoutingServiceClient

**File:** `backend/payment-service/src/main/java/com/payflow/payment/feign/RoutingServiceClient.java`

```java
package com.payflow.payment.feign;

import org.springframework.cloud.openfeign.FeignClient;
// @FeignClient — marks this interface as a Feign HTTP client
// Spring generates an implementation at runtime

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(
    name = "routing-service",
    // "routing-service" = the service name in Eureka
    // Feign looks up this name in Eureka to find the actual URL
    // e.g., Eureka tells Feign: routing-service is at http://192.168.1.5:8084
    
    url = "${routing-service.url:}",
    // Optional: override URL for testing (empty = use Eureka discovery)
    // In tests, you can set routing-service.url=http://localhost:8084
    
    fallback = RoutingServiceFallback.class
    // If routing-service is DOWN, use this fallback class
    // (circuit breaker integration)
)
public interface RoutingServiceClient {

    @PostMapping("/internal/route")
    // When called, Feign sends: POST http://routing-service/internal/route
    // With the Map serialized as JSON body
    Map<String, Object> routePayment(@RequestBody Map<String, Object> request);
    // Input: {merchantId, amount, currency, paymentMethod, cardBin, paymentId}
    // Output: {status: "AUTHORIZED", authorizationCode: "A12345", bankReferenceId: "..."}
    //     OR: {status: "FAILED", responseCode: "51", responseMessage: "Insufficient funds"}
}
```

**How Feign works under the hood:**
```
Your code calls: routingServiceClient.routePayment(requestMap)
                        │
                        ▼
Feign intercepts → Looks up "routing-service" in Eureka registry
                        │
                        ▼
Gets URL: http://10.0.0.5:8084 (from Eureka)
                        │
                        ▼
Sends HTTP: POST http://10.0.0.5:8084/internal/route
            Body: {"merchantId":"mer_abc","amount":"10000",...}
            Content-Type: application/json
                        │
                        ▼
Receives response → Deserializes JSON → Returns Map<String, Object>
```

### MerchantServiceClient

**File:** `backend/payment-service/src/main/java/com/payflow/payment/feign/MerchantServiceClient.java`

```java
package com.payflow.payment.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

@FeignClient(name = "merchant-service")
public interface MerchantServiceClient {

    @GetMapping("/internal/merchants/{merchantId}")
    Map<String, Object> getMerchant(@PathVariable("merchantId") String merchantId);
    // Used to validate that a merchant exists before creating an order
    // Returns merchant details or throws 404 if not found
}
```

---

<a name="order-controller"></a>
## Step 2: OrderController

**File:** `backend/payment-service/src/main/java/com/payflow/payment/controller/OrderController.java`

```java
package com.payflow.payment.controller;

import com.payflow.common.dto.ApiResponse;
// Our standard response wrapper: {success: true, data: {...}}

import com.payflow.payment.dto.CreateOrderRequest;
import com.payflow.payment.dto.OrderResponse;
import com.payflow.payment.service.OrderService;
import jakarta.validation.Valid;
// @Valid triggers Jakarta Bean Validation on the request body
// If validation fails (@NotNull, @Size, etc.), Spring returns 400 automatically

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
// @RestController = @Controller + @ResponseBody
// Every method returns JSON (not HTML views)

@RequestMapping("/v1/orders")
// Base path: all endpoints in this controller start with /v1/orders

@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * Create a new payment order.
     * Called by merchants to initiate a payment.
     *
     * curl -X POST http://localhost:8083/v1/orders \
     *   -H "Content-Type: application/json" \
     *   -H "X-API-Key: pk_abc123..." \
     *   -d '{"merchantId":"mer_001","amount":15000,"currency":"INR",...}'
     */
    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @Valid @RequestBody CreateOrderRequest request) {
        // @Valid → validate request fields (@NotNull, @NotBlank, @Positive)
        // @RequestBody → parse JSON body into CreateOrderRequest object

        OrderResponse response = orderService.createOrder(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)  // 201 Created (resource created)
                .body(ApiResponse.success(response));
    }

    /**
     * Get order by ID.
     *
     * curl http://localhost:8083/v1/orders/order_abc123
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(
            @PathVariable String orderId) {
        // @PathVariable extracts "order_abc123" from the URL

        OrderResponse response = orderService.getOrder(orderId);
        return ResponseEntity.ok(ApiResponse.success(response));
        // 200 OK
    }

    /**
     * List orders for a merchant (dashboard use).
     *
     * curl http://localhost:8083/v1/orders?merchantId=mer_001
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<OrderResponse>>> listOrders(
            @RequestParam String merchantId) {
        // @RequestParam extracts ?merchantId=mer_001 from query string

        List<OrderResponse> orders = orderService.getOrdersByMerchant(merchantId);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }
}
```

---

<a name="payment-controller"></a>
## Step 3: PaymentController — With Idempotency

**This is the most important controller — it integrates idempotency.**

**File:** `backend/payment-service/src/main/java/com/payflow/payment/controller/PaymentController.java`

```java
package com.payflow.payment.controller;

import com.payflow.common.dto.ApiResponse;
import com.payflow.payment.dto.*;
import com.payflow.payment.service.IdempotencyService;
import com.payflow.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;

    /**
     * AUTHORIZE a payment — the critical endpoint.
     *
     * REQUIRES Idempotency-Key header to prevent duplicate charges.
     *
     * curl -X POST http://localhost:8083/v1/payments/authorize \
     *   -H "Content-Type: application/json" \
     *   -H "Idempotency-Key: unique-request-id-12345" \
     *   -d '{"orderId":"order_abc123","paymentMethod":"CARD","cardNumber":"4111..."}'
     *
     * First call → processes payment, returns 201
     * Same Idempotency-Key again → returns cached response, 200 (not 201)
     */
    @PostMapping("/authorize")
    public ResponseEntity<ApiResponse<PaymentResponse>> authorize(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            // Idempotency-Key header: merchant sends a unique ID per request
            // If they retry (network timeout), same key → same response
            @Valid @RequestBody AuthorizePaymentRequest request) {

        // If no idempotency key provided, generate one (less safe but works)
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = java.util.UUID.randomUUID().toString();
        }

        // Step 1: Check if we already processed this key
        Optional<String> cached = idempotencyService.getCachedResponse(idempotencyKey);
        if (cached.isPresent()) {
            // Already processed — return the same response
            PaymentResponse cachedResponse = idempotencyService.deserialize(
                    cached.get(), PaymentResponse.class);
            log.info("Returning cached response for idempotency key: {}", idempotencyKey);
            return ResponseEntity.ok(ApiResponse.success(cachedResponse));
            // Note: 200 OK (not 201) for cached responses
        }

        // Step 2: Acquire lock (atomic — prevents race conditions)
        if (!idempotencyService.acquireLock(idempotencyKey)) {
            // Another request with same key is currently being processed
            // Return 409 Conflict — client should retry after a moment
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(
                            com.payflow.common.dto.ErrorResponse.of(
                                    "IDEMPOTENCY_CONFLICT",
                                    "Request is already being processed")));
        }

        try {
            // Step 3: Process the payment (the real work)
            PaymentResponse response = paymentService.authorize(request);

            // Step 4: Cache the response for future duplicate requests
            idempotencyService.cacheResponse(idempotencyKey, response);

            // 201 Created — new payment was created
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(response));

        } catch (Exception e) {
            // Step 5: On failure, release lock so merchant can retry
            idempotencyService.releaseLock(idempotencyKey);
            throw e; // Re-throw for exception handler to format as error response
        }
    }

    /**
     * CAPTURE a payment (collect the authorized money).
     *
     * curl -X POST http://localhost:8083/v1/payments/capture \
     *   -H "Content-Type: application/json" \
     *   -d '{"paymentId":"pay_xyz789","amount":15000}'
     */
    @PostMapping("/capture")
    public ResponseEntity<ApiResponse<PaymentResponse>> capture(
            @Valid @RequestBody CapturePaymentRequest request) {

        PaymentResponse response = paymentService.capture(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * VOID a payment (cancel authorization before capture).
     *
     * curl -X POST http://localhost:8083/v1/payments/pay_xyz789/void
     */
    @PostMapping("/{paymentId}/void")
    public ResponseEntity<ApiResponse<PaymentResponse>> voidPayment(
            @PathVariable String paymentId) {

        PaymentResponse response = paymentService.voidPayment(paymentId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * GET payment details.
     *
     * curl http://localhost:8083/v1/payments/pay_xyz789
     */
    @GetMapping("/{paymentId}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(
            @PathVariable String paymentId) {

        PaymentResponse response = paymentService.getPayment(paymentId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
```

---

<a name="refund-controller"></a>
## Step 4: RefundController

**File:** `backend/payment-service/src/main/java/com/payflow/payment/controller/RefundController.java`

```java
package com.payflow.payment.controller;

import com.payflow.common.dto.ApiResponse;
import com.payflow.payment.dto.RefundRequest;
import com.payflow.payment.dto.RefundResponse;
import com.payflow.payment.service.RefundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/refunds")
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;

    /**
     * Create a refund (full or partial).
     *
     * curl -X POST http://localhost:8083/v1/refunds \
     *   -H "Content-Type: application/json" \
     *   -d '{"paymentId":"pay_xyz789","amount":5000,"reason":"Customer returned item"}'
     */
    @PostMapping
    public ResponseEntity<ApiResponse<RefundResponse>> createRefund(
            @Valid @RequestBody RefundRequest request) {

        RefundResponse response = refundService.createRefund(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    /**
     * List refunds for a specific payment.
     *
     * curl http://localhost:8083/v1/refunds?paymentId=pay_xyz789
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<RefundResponse>>> listRefunds(
            @RequestParam String paymentId) {

        List<RefundResponse> refunds = refundService.getRefundsByPayment(paymentId);
        return ResponseEntity.ok(ApiResponse.success(refunds));
    }
}
```

---

<a name="exception-handler"></a>
## Step 5: PaymentExceptionHandler

**File:** `backend/payment-service/src/main/java/com/payflow/payment/exception/PaymentExceptionHandler.java`

```java
package com.payflow.payment.exception;

import com.payflow.common.dto.ApiResponse;
import com.payflow.common.dto.ErrorResponse;
import com.payflow.common.dto.ValidationError;
import com.payflow.common.exception.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
// @RestControllerAdvice = exception handler that applies to ALL controllers in this service
// Any exception thrown from any controller method gets caught here
@Slf4j
public class PaymentExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    // Catches: throw new ResourceNotFoundException("Payment", "pay_xxx")
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)    // 404
                .body(ApiResponse.error(ErrorResponse.of(ex.getErrorCode(), ex.getMessage())));
    }

    @ExceptionHandler(PayflowException.class)
    // Catches: throw new PayflowException("INVALID_STATE", "Cannot capture...")
    public ResponseEntity<ApiResponse<Void>> handlePayflow(PayflowException ex) {
        log.warn("Business error: {} - {}", ex.getErrorCode(), ex.getMessage());

        HttpStatus status = switch (ex.getErrorCode()) {
            case "INVALID_STATE", "DUPLICATE_PAYMENT", "REFUND_EXCEEDS_AMOUNT" ->
                    HttpStatus.UNPROCESSABLE_ENTITY;  // 422
            case "IDEMPOTENCY_CONFLICT" -> HttpStatus.CONFLICT;  // 409
            default -> HttpStatus.BAD_REQUEST;  // 400
        };
        // switch expression (Java 14+): concise pattern matching

        return ResponseEntity.status(status)
                .body(ApiResponse.error(ErrorResponse.of(ex.getErrorCode(), ex.getMessage())));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleIdempotency(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)  // 409
                .body(ApiResponse.error(ErrorResponse.of(ex.getErrorCode(), ex.getMessage())));
    }

    @ExceptionHandler(PaymentDeclinedException.class)
    public ResponseEntity<ApiResponse<Void>> handleDeclined(PaymentDeclinedException ex) {
        log.info("Payment declined: {} - {}", ex.getDeclineCode(), ex.getDeclineReason());
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)  // 402
                .body(ApiResponse.error(ErrorResponse.of(ex.getErrorCode(), ex.getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    // Catches: @Valid validation failures (missing required fields, wrong format)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<ValidationError> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toValidationError)
                .toList();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)  // 400
                .body(ApiResponse.error(
                        ErrorResponse.withDetails("VALIDATION_ERROR", "Request validation failed", details)));
    }

    @ExceptionHandler(Exception.class)
    // Catch-all: anything unexpected (NullPointerException, etc.)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unexpected error: ", ex);
        // Log full stack trace for debugging
        // But DON'T expose internal details to the client (security!)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)  // 500
                .body(ApiResponse.error(ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred")));
    }

    private ValidationError toValidationError(FieldError fe) {
        return ValidationError.builder()
                .field(fe.getField())
                .message(fe.getDefaultMessage())
                .rejectedValue(fe.getRejectedValue())
                .build();
    }
}
```

---

<a name="feign-config"></a>
## Step 6: FeignConfig

**File:** `backend/payment-service/src/main/java/com/payflow/payment/config/FeignConfig.java`

```java
package com.payflow.payment.config;

import feign.Logger;
import feign.Request;
import feign.Retryer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class FeignConfig {

    @Bean
    public Request.Options requestOptions() {
        // Timeouts for Feign HTTP calls
        return new Request.Options(
                5, TimeUnit.SECONDS,    // Connect timeout: 5 sec
                10, TimeUnit.SECONDS,   // Read timeout: 10 sec (bank may be slow)
                true                     // Follow redirects
        );
        // WHY 10 sec read timeout?
        // routing-service calls the bank (ISO 8583 TCP).
        // Bank can take up to 5 seconds to respond.
        // We add buffer: 5 (bank) + 5 (network) = 10 sec max
    }

    @Bean
    public Retryer retryer() {
        // Retry failed Feign calls up to 3 times
        return new Retryer.Default(
                100,   // Initial interval: 100ms
                1000,  // Max interval: 1000ms (1 sec)
                3      // Max attempts: 3
        );
        // Only retries on connection errors, NOT on 4xx/5xx responses
        // A 404 or 500 from routing-service won't be retried
    }

    @Bean
    public Logger.Level feignLogLevel() {
        return Logger.Level.BASIC;
        // NONE: no logging
        // BASIC: method + URL + status + time
        // HEADERS: BASIC + request/response headers
        // FULL: HEADERS + body (only for debugging — verbose!)
    }
}
```

---

<a name="security-config"></a>
## Step 7: SecurityConfig

**File:** `backend/payment-service/src/main/java/com/payflow/payment/config/SecurityConfig.java`

```java
package com.payflow.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                // Disable CSRF: we're a stateless API, not a form-based app
                // CSRF protection is for browser-submitted forms
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // No HTTP sessions: every request is independently authenticated
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v1/**").permitAll()
                        // Allow all /v1/ endpoints
                        // Real auth is handled by API Gateway (JWT/API Key)
                        // Individual services trust the gateway's validation
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/internal/**").permitAll()
                        // Internal endpoints (called by other services via Feign)
                        .anyRequest().authenticated()
                )
                .build();
    }
}
```

---

<a name="tests"></a>
## Step 8: Unit Tests

### PaymentServiceTest (Key Tests)

**File:** `backend/payment-service/src/test/java/com/payflow/payment/service/PaymentServiceTest.java`

The test file already exists (created earlier). Here's what each test verifies:

```java
@Test
@DisplayName("authorize - should create payment and route to bank successfully")
void authorize_Success() {
    // GIVEN: valid order in CREATED state, routing-service returns AUTHORIZED
    // WHEN: paymentService.authorize(request)
    // THEN: payment status = AUTHORIZED, order status = ATTEMPTED
    //       event published to "payment.authorized" topic
}

@Test
@DisplayName("capture - should change payment status from AUTHORIZED to CAPTURED")
void capture_ChangesStatus() {
    // GIVEN: payment in AUTHORIZED state
    // WHEN: paymentService.capture(request)
    // THEN: payment status = CAPTURED, order status = PAID
    //       event published to "payment.captured" topic
}

@Test
@DisplayName("capture - should throw PayflowException for invalid state transition")
void capture_InvalidState_Throws() {
    // GIVEN: payment in CAPTURED state (already captured!)
    // WHEN: paymentService.capture(request)
    // THEN: throws PayflowException with code "INVALID_STATE"
    //       payment remains CAPTURED (no change)
}
```

### Running the Tests

```powershell
cd backend
mvn test -pl payment-service
# Expected: Tests run: X, Failures: 0, Errors: 0
```

---

<a name="verification"></a>
## Verification — Full Payment Flow

### Prerequisites
```powershell
# Start infrastructure
cd infra/docker && docker compose up postgres redis kafka -d

# Start required services (in separate terminals)
cd backend
mvn spring-boot:run -pl service-registry
mvn spring-boot:run -pl config-server
mvn spring-boot:run -pl payment-service
```

### Test with curl

```powershell
# 1. Create Order
curl -X POST http://localhost:8083/v1/orders `
  -H "Content-Type: application/json" `
  -d '{\"merchantId\":\"mer_test001\",\"amount\":15000,\"currency\":\"INR\",\"customerEmail\":\"test@example.com\",\"description\":\"Test order\"}'

# Expected: 201 Created
# {"success":true,"data":{"id":"order_abc123...","status":"CREATED",...}}

# 2. Authorize Payment (with idempotency key)
curl -X POST http://localhost:8083/v1/payments/authorize `
  -H "Content-Type: application/json" `
  -H "Idempotency-Key: test-key-001" `
  -d '{\"orderId\":\"order_abc123...\",\"paymentMethod\":\"CARD\",\"cardNumber\":\"4111111111111111\",\"cardExpiryMonth\":\"12\",\"cardExpiryYear\":\"2026\"}'

# Expected: 201 Created (first call)
# {"success":true,"data":{"id":"pay_xyz789...","status":"AUTHORIZED",...}}

# 3. Same request again (duplicate — idempotency!)
# Same curl as above → Expected: 200 OK (cached response, NOT a new charge)

# 4. Capture
curl -X POST http://localhost:8083/v1/payments/capture `
  -H "Content-Type: application/json" `
  -d '{\"paymentId\":\"pay_xyz789...\",\"amount\":15000}'

# Expected: 200 OK, status = "CAPTURED"

# 5. Refund (partial)
curl -X POST http://localhost:8083/v1/refunds `
  -H "Content-Type: application/json" `
  -d '{\"paymentId\":\"pay_xyz789...\",\"amount\":5000,\"reason\":\"Customer returned\"}'

# Expected: 201 Created
```

**Note:** The authorize call will FAIL with a connection error if routing-service isn't running (expected behavior — it tries to call the bank). Once routing-service and bank-simulator are built (Part 9-10), the full flow will work end-to-end.

---

## What You Learned

| # | Concept | What You Practiced |
|---|---------|-------------------|
| 1 | Feign Client | Declarative HTTP client with Eureka discovery |
| 2 | @RestController | REST endpoints with proper HTTP status codes |
| 3 | Idempotency in API | Controller-level check-lock-execute-cache pattern |
| 4 | @ExceptionHandler | Global error handling with structured JSON responses |
| 5 | HTTP Status Codes | 201 (created), 200 (ok), 404 (not found), 409 (conflict), 422 (invalid state) |
| 6 | @Valid | Jakarta validation on request DTOs |
| 7 | Feign timeouts & retries | Configurable connection/read timeouts, retry policy |
| 8 | SecurityConfig | Stateless, CSRF disabled, permit public endpoints |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `No qualifying bean: RoutingServiceClient` | @EnableFeignClients missing on Application class | Add `@EnableFeignClients` to PaymentServiceApplication |
| `Connection refused` on Feign call | Target service not running | Start routing-service (or use fallback) |
| `405 Method Not Allowed` | Wrong HTTP method (GET instead of POST) | Check your curl command matches the endpoint annotation |
| `415 Unsupported Media Type` | Missing Content-Type header | Add `-H "Content-Type: application/json"` to curl |
| `400 Bad Request` with validation errors | Required fields missing in JSON body | Check request DTO — all @NotNull/@NotBlank fields must be present |
| `409 Conflict` on authorize | Same idempotency key already processing | Wait 1 second and retry, or use different key |
| `422 Unprocessable Entity` | Invalid state transition | Check current payment status before calling capture/void |
| `NullPointerException in Feign` | Service name mismatch in @FeignClient | Ensure name matches exactly what's registered in Eureka |

---

## Git Commit

```bash
git add .
git status
# Review: controllers, Feign clients, exception handler, security config, tests

git commit -m "Phase 4 Part 8c: Add payment controllers, Feign clients, and tests

- Created OrderController (/v1/orders — create, get, list)
- Created PaymentController (/v1/payments — authorize with idempotency, capture, void, get)
- Created RefundController (/v1/refunds — create, list)
- Created RoutingServiceClient (Feign → routing-service)
- Created MerchantServiceClient (Feign → merchant-service)
- Created PaymentExceptionHandler (global error handling)
- Created FeignConfig (timeouts: 5s connect, 10s read, 3 retries)
- Created SecurityConfig (stateless, CSRF disabled)
- Verified: mvn test passes, endpoints respond correctly"

git push origin main
```

---

## Document Index

| Phase | Part | Document | Status |
|-------|------|----------|--------|
| 4 | 8a | [Payment Entities](./phase4-part08a-payment-entities.md) | ✅ |
| 4 | 8b | [Payment Services & Kafka](./phase4-part08b-payment-services-kafka.md) | ✅ |
| 4 | **8c** | **[Payment Controllers](./phase4-part08c-payment-controllers.md)** | ← You are here |
| 4 | 9a | [Routing — ISO 8583](./phase4-part09a-routing-iso8583.md) | ⬜ Next |
| 4 | 9b | [Routing — Netty TCP](./phase4-part09b-routing-netty.md) | ⬜ |
| 4 | 9c | [Routing — Fraud & Smart Routing](./phase4-part09c-routing-fraud-smartrouting.md) | ⬜ |

---

## Next Steps

**What's coming in Part 9a (Routing Service — ISO 8583):**
- Implement ISO 8583 message builder and parser from scratch
- Binary protocol encoding (bitmap, field packing)
- Message constants (MTI codes, field numbers, response codes)
- Unit tests for message roundtrip (build → encode → decode → verify)

**Before moving on, verify:**
- [ ] `mvn clean compile -pl payment-service -am` → BUILD SUCCESS
- [ ] `mvn test -pl payment-service` → all tests pass
- [ ] Payment endpoints respond when service is running
- [ ] Git commit pushed

---
*End of Phase 4 Part 8c — Payment Controllers, Feign Clients & Tests*
*Next: [Phase 4 Part 9a — Routing Service: ISO 8583 Implementation](./phase4-part09a-routing-iso8583.md)*
