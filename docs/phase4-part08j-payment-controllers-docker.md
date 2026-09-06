# 🏗️ Phase 4 Part 8j: Payment Service — Controllers + ExceptionHandler + FeignClients + Dockerfile + curl

> **"Three controllers, two Feign clients, one exception handler, and an idempotency-key header that prevents double-charging customers."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 8j — Controllers + Feign + Exception Handler + Docker + curl |
| **What You Build** | PaymentController.java, OrderController.java, RefundController.java, PaymentExceptionHandler.java, RoutingServiceClient.java, MerchantServiceClient.java, PaymentControllerTest.java, Dockerfile |
| **Previous** | [Part 8i — PaymentService Core Engine](./phase4-part08i-payment-engine.md) |
| **Next** | [Part 8k — Connections & Flows](./phase4-part08k-payment-connections-flows.md) |

---

## 📖 Table of Contents

1. [Overview — 8 Files in This Part](#1-overview--8-files-in-this-part)
2. [Step-by-Step: RoutingServiceClient.java (Feign)](#2-step-by-step-routingserviceclientjava-feign)
3. [Step-by-Step: MerchantServiceClient.java (Feign)](#3-step-by-step-merchantserviceclientjava-feign)
4. [Step-by-Step: PaymentExceptionHandler.java](#4-step-by-step-paymentexceptionhandlerjava)
5. [Step-by-Step: OrderController.java](#5-step-by-step-ordercontrollerjava)
6. [Step-by-Step: RefundController.java](#6-step-by-step-refundcontrollerjava)
7. [Step-by-Step: PaymentController.java (with Idempotency)](#7-step-by-step-paymentcontrollerjava-with-idempotency)
8. [Step-by-Step: PaymentControllerTest.java](#8-step-by-step-paymentcontrollertestjava)
9. [Step-by-Step: Dockerfile](#9-step-by-step-dockerfile)
10. [Testing with curl — Complete Payment Flow](#10-testing-with-curl--complete-payment-flow)
11. [What You Learned](#11-what-you-learned)

---

## 1. Overview — 8 Files in This Part

| File | Type | What's NEW vs Merchant |
|---|---|---|
| `RoutingServiceClient.java` | 🆕 Feign Client | **Completely new** — interface becomes HTTP client |
| `MerchantServiceClient.java` | 🆕 Feign Client | **Completely new** — calls merchant-service |
| `PaymentExceptionHandler.java` | Exception Handler | +`PaymentDeclinedException → 402`, +`IdempotencyConflictException → 409`, +`PayflowException → 422` |
| `OrderController.java` | Controller | +`@Tag` Swagger annotation, +`@Operation` per endpoint |
| `RefundController.java` | Controller | Standard pattern |
| `PaymentController.java` | 🆕 Controller | **Idempotency-Key header** integration — completely new pattern |
| `PaymentControllerTest.java` | Test | Tests idempotency cache hit |
| `Dockerfile` | Docker | Port 8083 (not 8082) |

---

## 2. Step-by-Step: RoutingServiceClient.java (Feign)

**File:** `src/main/java/com/payflow/payment/feign/RoutingServiceClient.java`

```java
package com.payflow.payment.feign;

import com.payflow.payment.config.FeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * Feign client for the routing-service.
 * Routes payment transactions to the appropriate bank/PSP via internal API.
 */
@FeignClient(
        name = "routing-service",
        configuration = FeignConfig.class,
        path = "/internal"
)
public interface RoutingServiceClient {

    @PostMapping("/route")
    Map<String, Object> routePayment(@RequestBody Map<String, Object> request);
}
```

**🆕 `@FeignClient` — THE MOST IMPORTANT NEW ANNOTATION**

This is an **interface** (not a class). Spring + Feign generate a complete HTTP client implementation at runtime.

| Attribute | Value | Meaning |
|---|---|---|
| `name = "routing-service"` | Eureka service name | Feign looks up `routing-service` in Eureka → gets IP:port |
| `configuration = FeignConfig.class` | Our custom config | Uses 5s connect / 10s read timeouts, 3 retries, BASIC logging |
| `path = "/internal"` | Base path | All methods' paths are relative to `/internal` |

**`@PostMapping("/route")`** — Combined with `path = "/internal"`, the full URL is:
```
POST http://routing-service/internal/route
     └─── Eureka name ───┘└── path ──┘└ method path ┘
```

**`Map<String, Object>`** — loosely typed request and response. The routing-service expects/returns dynamic fields.

**WHAT FEIGN GENERATES AT RUNTIME:**
```java
// Feign generates something equivalent to:
public class RoutingServiceClient_FeignImpl implements RoutingServiceClient {
    @Override
    public Map<String, Object> routePayment(Map<String, Object> request) {
        // 1. Look up "routing-service" in Eureka → http://192.168.x.x:8084
        // 2. Serialize request Map → JSON
        // 3. POST http://192.168.x.x:8084/internal/route (JSON body)
        // 4. Apply FeignConfig: 5s connect, 10s read, retry 3x
        // 5. Deserialize JSON response → Map<String, Object>
        // 6. Return the Map
    }
}
```

**YOU NEVER WRITE THIS CLASS.** Feign generates it from the interface + annotations. That's the magic of `@EnableFeignClients` in the main class.

---

## 3. Step-by-Step: MerchantServiceClient.java (Feign)

**File:** `src/main/java/com/payflow/payment/feign/MerchantServiceClient.java`

```java
package com.payflow.payment.feign;

import com.payflow.payment.config.FeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

@FeignClient(
        name = "merchant-service",
        configuration = FeignConfig.class,
        path = "/internal/merchants"
)
public interface MerchantServiceClient {

    @GetMapping("/{merchantId}")
    Map<String, Object> getMerchant(@PathVariable("merchantId") String merchantId);

    @GetMapping("/{merchantId}/validate")
    Map<String, Object> validateMerchant(@PathVariable("merchantId") String merchantId);
}
```

**TWO METHODS:** Get merchant details and validate merchant exists.

**`@PathVariable("merchantId")`** — Note the explicit name `"merchantId"`. In Feign, you MUST specify the path variable name (unlike Spring MVC where it's inferred from the parameter name).

**CURRENTLY NOT USED** in PaymentService (merchant validation is done at the Gateway level via API keys). Defined for future use — when Payment Service needs to verify merchant configuration directly.

---

## 4. Step-by-Step: PaymentExceptionHandler.java

**File:** `src/main/java/com/payflow/payment/exception/PaymentExceptionHandler.java`

### What's NEW vs Identity/Merchant ExceptionHandler

| Exception | HTTP Status | NEW? |
|---|---|---|
| `ResourceNotFoundException` | 404 Not Found | Same as identity/merchant |
| `DuplicateResourceException` | 409 Conflict | Same |
| `MethodArgumentNotValidException` | 400 Bad Request | Same |
| `Exception` (catch-all) | 500 Internal Server Error | Same |
| **`PaymentDeclinedException`** | **402 Payment Required** | 🆕 **NEW** |
| **`IdempotencyConflictException`** | **409 Conflict** | 🆕 **NEW** |
| **`PayflowException`** | **422 Unprocessable Entity** | 🆕 **NEW** |

### The 3 New Exception Handlers

```java
    @ExceptionHandler(PaymentDeclinedException.class)
    public ResponseEntity<ApiResponse<Void>> handleDeclined(PaymentDeclinedException ex) {
        log.warn("Payment declined: {} - {}", ex.getDeclineCode(), ex.getDeclineReason());
        ErrorResponse error = ErrorResponse.of(ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(ApiResponse.error(error));
    }
```

**🆕 HTTP 402 PAYMENT REQUIRED**

One of the rarest HTTP status codes — but perfect for payment systems. Means "the request can't be fulfilled because payment is needed" (or in our case, "payment was attempted but the bank declined").

```java
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleIdempotencyConflict(IdempotencyConflictException ex) {
        log.warn("Idempotency conflict: {}", ex.getMessage());
        ErrorResponse error = ErrorResponse.of(ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(error));
    }
```

**409 Conflict** — "A concurrent request with the same idempotency key is currently being processed. Try again in a moment."

```java
    @ExceptionHandler(PayflowException.class)
    public ResponseEntity<ApiResponse<Void>> handlePayflowException(PayflowException ex) {
        log.error("Application error: [{}] {}", ex.getErrorCode(), ex.getMessage());
        ErrorResponse error = ErrorResponse.of(ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error(error));
    }
```

**🆕 HTTP 422 UNPROCESSABLE ENTITY** — "I understand your request, but the business logic rejects it" (expired order, invalid state transition, capture amount exceeds authorized, etc.).

| Code | When |
|---|---|
| 400 | Request format is wrong (validation error) |
| **422** | **Request format is correct but BUSINESS RULES reject it** |

### Exception → HTTP Status Complete Mapping

| Exception | Code | Status | Example |
|---|---|---|---|
| `ResourceNotFoundException` | varies | 404 | "Payment pay_abc not found" |
| `PaymentDeclinedException` | PAYMENT_DECLINED | **402** | "Bank declined: insufficient funds" |
| `IdempotencyConflictException` | IDEMPOTENCY_CONFLICT | **409** | "Request with this key is in progress" |
| `DuplicateResourceException` | DUPLICATE_RESOURCE | 409 | "Order already has a payment" |
| `PayflowException` | varies | **422** | "Cannot capture VOIDED payment" |
| `MethodArgumentNotValidException` | VALIDATION_ERROR | 400 | "Amount must be at least 0.01" |
| `Exception` (any other) | INTERNAL_ERROR | 500 | "Unexpected error" |

---

## 5. Step-by-Step: OrderController.java

**File:** `src/main/java/com/payflow/payment/controller/OrderController.java`

```java
@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Payment order management APIs")
public class OrderController {

    private final OrderService orderService;
```

**🆕 `@Tag` — SWAGGER ANNOTATION**

Groups endpoints in Swagger UI under "Orders". When you open http://localhost:8083/swagger-ui.html, you'll see:
```
Orders ▼
  POST /v1/orders             Create a new payment order
  GET /v1/orders/{orderId}    Get order by ID
  GET /v1/orders              List orders by merchant
  POST /v1/orders/expire      Expire stale orders

Payments ▼
  ...

Refunds ▼
  ...
```

### 4 Endpoints

```java
    @PostMapping
    @Operation(summary = "Create a new payment order")
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @Valid @RequestBody CreateOrderRequest request) {
        OrderResponse response = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get order by ID")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(@PathVariable String orderId) {
        OrderResponse response = orderService.getOrder(orderId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping
    @Operation(summary = "List orders by merchant")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> listOrders(@RequestParam String merchantId) {
        List<OrderResponse> orders = orderService.listByMerchant(merchantId);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @PostMapping("/expire")
    @Operation(summary = "Expire stale orders (admin/internal)")
    public ResponseEntity<ApiResponse<Integer>> expireOrders() {
        int count = orderService.expireOrders();
        return ResponseEntity.ok(ApiResponse.success(count));
    }
```

**`@Operation(summary = "...")`** — Swagger description for each endpoint. Appears next to the endpoint in Swagger UI.

**`POST /expire`** returns `Integer` — the number of orders expired. Useful for admin dashboards/cron monitoring.

---

## 6. Step-by-Step: RefundController.java

Standard pattern — 3 endpoints, no idempotency, thin controller.

```java
@RestController
@RequestMapping("/v1/refunds")
@RequiredArgsConstructor
@Tag(name = "Refunds", description = "Refund management APIs")
public class RefundController {

    private final RefundService refundService;

    @PostMapping
    public ResponseEntity<ApiResponse<RefundResponse>> createRefund(
            @Valid @RequestBody RefundRequest request) {
        RefundResponse response = refundService.createRefund(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/{refundId}")
    public ResponseEntity<ApiResponse<RefundResponse>> getRefund(@PathVariable String refundId) {
        RefundResponse response = refundService.getRefund(refundId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<RefundResponse>>> listRefunds(@RequestParam String paymentId) {
        List<RefundResponse> refunds = refundService.listByPayment(paymentId);
        return ResponseEntity.ok(ApiResponse.success(refunds));
    }
}
```

---

## 7. Step-by-Step: PaymentController.java (with Idempotency)

**File:** `src/main/java/com/payflow/payment/controller/PaymentController.java`

This is the **most complex controller** in the entire project because of idempotency integration.

### Class Declaration

```java
@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payments", description = "Payment processing APIs — authorize, capture, void")
public class PaymentController {

    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;
```

**TWO DEPENDENCIES** — unlike Order/Refund controllers which have one.

### authorize() — The Idempotency Flow

```java
    @PostMapping("/authorize")
    public ResponseEntity<ApiResponse<PaymentResponse>> authorize(
            @Valid @RequestBody AuthorizePaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
```

**🆕 `@RequestHeader(value = "Idempotency-Key", required = false)`**

| Part | Meaning |
|---|---|
| `value = "Idempotency-Key"` | Read the HTTP header named `Idempotency-Key` |
| `required = false` | Header is OPTIONAL — `null` if not provided |
| `String idempotencyKey` | The header value (or null) |

```
Client sends:
  POST /v1/payments/authorize
  Idempotency-Key: unique-uuid-abc-123       ← optional header
  Content-Type: application/json
  { "orderId": "order_xyz", ... }
```

**WHY OPTIONAL?** Idempotency is a safety feature the client can opt into. Without the header, the payment processes normally (no caching).

```java
        // Check idempotency
        if (idempotencyKey != null) {
            Optional<String> cached = idempotencyService.getCachedResponse(idempotencyKey);
            if (cached.isPresent()) {
                PaymentResponse cachedResponse = idempotencyService.deserialize(
                        cached.get(), PaymentResponse.class);
                return ResponseEntity.ok(ApiResponse.success(cachedResponse));
            }
```

**CACHE HIT:** Key exists in Redis with a JSON response → return it immediately. No payment processing. **HTTP 200 (not 201)** — because we're returning a CACHED result, not creating a new resource.

```java
            if (!idempotencyService.acquireLock(idempotencyKey)) {
                Optional<String> retryCache = idempotencyService.getCachedResponse(idempotencyKey);
                if (retryCache.isPresent()) {
                    PaymentResponse cachedResponse = idempotencyService.deserialize(
                            retryCache.get(), PaymentResponse.class);
                    return ResponseEntity.ok(ApiResponse.success(cachedResponse));
                }
            }
        }
```

**LOCK FAILED:** Another thread locked the key between our check and lock attempt. Retry the cache check — the other thread might have finished by now.

**This handles the RACE CONDITION:**
```
Thread A: getCachedResponse → null, acquireLock → true → processing...
Thread B: getCachedResponse → null, acquireLock → false → retry getCachedResponse → maybe cached now
```

```java
        try {
            PaymentResponse response = paymentService.authorize(request);

            if (idempotencyKey != null) {
                idempotencyService.cacheResponse(idempotencyKey, response);
            }

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(response));
```

**SUCCESS:** Process payment → cache result → return 201 Created.

```java
        } catch (Exception e) {
            if (idempotencyKey != null) {
                idempotencyService.releaseLock(idempotencyKey);
            }
            throw e;
        }
    }
```

**FAILURE:** Release the lock so the client can retry with the same key. Without this, a failed payment would leave a "PROCESSING" lock for 24 hours.

### capture() — Same Idempotency Pattern

```java
    @PostMapping("/capture")
    public ResponseEntity<ApiResponse<PaymentResponse>> capture(
            @Valid @RequestBody CapturePaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // ... same idempotency check/lock/cache/release pattern as authorize
    }
```

Identical idempotency flow. Capture returns **200 OK** (not 201 — capturing an existing resource, not creating).

### void + getPayment — No Idempotency

```java
    @PostMapping("/{paymentId}/void")
    public ResponseEntity<ApiResponse<PaymentResponse>> voidPayment(@PathVariable String paymentId) {
        PaymentResponse response = paymentService.voidPayment(paymentId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(@PathVariable String paymentId) {
        PaymentResponse response = paymentService.getPayment(paymentId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
```

**WHY NO IDEMPOTENCY ON VOID/GET?**
- **Void** is naturally idempotent — voiding an already-voided payment throws "INVALID_STATE". No risk of double action.
- **GET** is naturally idempotent — reading data never changes state.

---

## 8. Step-by-Step: PaymentControllerTest.java

### 4 Tests

| Test | What It Tests |
|---|---|
| `authorize_ReturnsCreated` | Happy path: authorize with idempotency key → 201 |
| `capture_ReturnsOk` | Happy path: capture → 200 |
| `getPayment_ReturnsOk` | GET payment by ID → 200 |
| `authorize_CachedIdempotencyKey_ReturnsCached` | 🆕 Duplicate key → returns cached response → 200 |

### The Idempotency Cache Hit Test

```java
    @Test
    @DisplayName("POST /authorize - should return cached response for duplicate idempotency key")
    void authorize_CachedIdempotencyKey_ReturnsCached() throws Exception {
        // ... setup ...

        String cachedJson = objectMapper.writeValueAsString(mockPaymentResponse);
        when(idempotencyService.getCachedResponse("duplicate-key")).thenReturn(Optional.of(cachedJson));
        when(idempotencyService.deserialize(cachedJson, PaymentResponse.class))
                .thenReturn(mockPaymentResponse);

        mockMvc.perform(post("/v1/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "duplicate-key")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())       // 200 (not 201!)
                .andExpect(jsonPath("$.data.id").value("pay-001"));
    }
```

**KEY ASSERTION:** Status is **200 OK** (cached), not **201 Created** (new). This proves the idempotency logic works — duplicate requests return the cached result without processing.

**`.header("Idempotency-Key", "duplicate-key")`** — MockMvc can set custom HTTP headers.

---

## 9. Step-by-Step: Dockerfile

Same pattern as merchant but port 8083:

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
COPY common-lib ./common-lib
# ... all modules ...
RUN mvn clean package -pl payment-service -am -DskipTests -B

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S payflow && adduser -S payflow -G payflow
USER payflow
COPY --from=builder /app/payment-service/target/*.jar app.jar
EXPOSE 8083
HEALTHCHECK --interval=15s --timeout=10s --retries=5 --start-period=30s \
    CMD wget -qO- http://localhost:8083/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## 10. Testing with curl — Complete Payment Flow

### Prerequisites

```bash
# Start infrastructure
cd infra/docker && docker-compose up -d postgres redis kafka

# Build common-lib
cd backend && mvn install -pl common-lib -am -DskipTests

# Start payment service
cd payment-service && mvn spring-boot:run
```

### Step 1: Create Order

```bash
curl -s -X POST http://localhost:8083/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "merchantId": "merchant-001",
    "amount": 1500.00,
    "currency": "INR",
    "customerEmail": "customer@example.com",
    "description": "Premium Plan"
  }' | jq

# Save the order ID:
ORDER_ID="order_xxxxxx"
```

### Step 2: Authorize Payment (with Idempotency Key)

```bash
curl -s -X POST http://localhost:8083/v1/payments/authorize \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: unique-uuid-$(uuidgen)" \
  -d "{
    \"orderId\": \"$ORDER_ID\",
    \"paymentMethod\": \"CARD\",
    \"cardNumber\": \"4111111111111111\",
    \"cardCvv\": \"123\",
    \"cardExpiryMonth\": \"12\",
    \"cardExpiryYear\": \"2026\"
  }" | jq

# Save the payment ID:
PAYMENT_ID="pay_xxxxxx"
```

### Step 3: Capture Payment

```bash
curl -s -X POST http://localhost:8083/v1/payments/capture \
  -H "Content-Type: application/json" \
  -d "{
    \"paymentId\": \"$PAYMENT_ID\",
    \"amount\": 1500.00
  }" | jq
```

### Step 4: Create Refund (Partial)

```bash
curl -s -X POST http://localhost:8083/v1/refunds \
  -H "Content-Type: application/json" \
  -d "{
    \"paymentId\": \"$PAYMENT_ID\",
    \"amount\": 500.00,
    \"reason\": \"Customer returned one item\"
  }" | jq
```

### Step 5: Get Payment Details

```bash
curl -s http://localhost:8083/v1/payments/$PAYMENT_ID | jq
```

### Step 6: Test Idempotency (Same Key Returns Cached)

```bash
IDEM_KEY="test-idem-$(uuidgen)"

# First call — processes payment
curl -s -X POST http://localhost:8083/v1/payments/authorize \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEM_KEY" \
  -d '{"orderId":"'$ORDER_ID'","paymentMethod":"CARD","cardNumber":"4111111111111111","cardExpiryMonth":"12","cardExpiryYear":"2026"}' | jq

# Second call with SAME key — returns cached (no second charge!)
curl -s -X POST http://localhost:8083/v1/payments/authorize \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEM_KEY" \
  -d '{"orderId":"'$ORDER_ID'","paymentMethod":"CARD","cardNumber":"4111111111111111","cardExpiryMonth":"12","cardExpiryYear":"2026"}' | jq
# Should return same response with HTTP 200 (not 201)
```

### Step 7: Test Over-Refund Prevention

```bash
# Try to refund more than remaining
curl -s -X POST http://localhost:8083/v1/refunds \
  -H "Content-Type: application/json" \
  -d "{
    \"paymentId\": \"$PAYMENT_ID\",
    \"amount\": 2000.00,
    \"reason\": \"Too much\"
  }" | jq
# Should return 422: "Refund amount exceeds remaining refundable amount"
```

### Step 8: Expire Stale Orders

```bash
curl -s -X POST http://localhost:8083/v1/orders/expire | jq
# Returns: {"data": 0}  (or the count of expired orders)
```

---

## 11. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **@FeignClient** | Interface → Spring generates HTTP client at runtime. No implementation class needed. |
| 2 | **Feign name = Eureka name** | `name = "routing-service"` → Eureka lookup → real IP:port |
| 3 | **Feign path** | Base path for all methods: `path = "/internal"` + `@PostMapping("/route")` = `/internal/route` |
| 4 | **@PathVariable in Feign** | Must specify name explicitly: `@PathVariable("merchantId")` |
| 5 | **HTTP 402 Payment Required** | Bank declined the payment — specific to payment systems |
| 6 | **HTTP 422 Unprocessable Entity** | Business logic rejected the request (not validation, but rules) |
| 7 | **Idempotency-Key header** | `@RequestHeader(required = false)` — optional, client opts in |
| 8 | **Cache hit → 200** | Duplicate request returns cached result with HTTP 200 (not 201) |
| 9 | **Lock failure → retry cache** | Race condition handling: another thread may have finished |
| 10 | **Release lock on failure** | Exception → release Redis lock → client can retry |
| 11 | **@Tag + @Operation** | Swagger UI organization — groups endpoints, describes each one |
| 12 | **3 separate controllers** | Orders, Payments, Refunds — each has its own base path |
| 13 | **Void has no idempotency** | Naturally idempotent — voiding twice throws INVALID_STATE |
| 14 | **Feign clients are interfaces** | No `.java` implementation class — just an interface with annotations |
| 15 | **Port 8083** | Payment service runs on 8083 (identity=8081, merchant=8082) |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part08-payment-service-overview.md) | Payment Service Overview |
| [Part 8a](./phase4-part08a-payment-project-setup.md) | Project Setup |
| [Part 8b](./phase4-part08b-payment-entities.md) | Entities |
| [Part 8c](./phase4-part08c-payment-migrations.md) | Flyway Migrations |
| [Part 8d](./phase4-part08d-payment-repositories.md) | Repositories |
| [Part 8e](./phase4-part08e-payment-dtos-mappers.md) | DTOs + Mappers |
| [Part 8f](./phase4-part08f-payment-configs.md) | Config Classes |
| [Part 8g](./phase4-part08g-payment-order-refund-services.md) | OrderService + RefundService |
| [Part 8h](./phase4-part08h-payment-idempotency-events.md) | IdempotencyService + Events |
| [Part 8i](./phase4-part08i-payment-engine.md) | PaymentService Core Engine |
| **Part 8j** | **Controllers + Docker** (You are here) |
| [Part 8k](./phase4-part08k-payment-connections-flows.md) | Connections & Flows |

---

*Next: [Part 8k — How Everything Connects](./phase4-part08k-payment-connections-flows.md) →*
