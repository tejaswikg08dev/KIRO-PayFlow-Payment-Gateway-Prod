# Spring Cloud Code Flow — PayFlow Payment Gateway

| Field | Details |
|-------|---------|
| **Project** | PayFlow Payment Gateway |
| **Document Type** | Request Flow & Code Path Reference |
| **Focus** | End-to-end journey of POST /v1/payments/authorize |
| **Last Updated** | 2024 |
| **Audience** | Developers tracing request paths, Interview preparation |

---

## Table of Contents
- [Complete Request Journey](#request-journey)
- [Step-by-Step: POST /v1/payments/authorize](#step-by-step)
- [Gateway Layer](#gateway-layer)
- [Service Layer](#service-layer)
- [Inter-Service Communication](#inter-service)
- [Async Events](#async-events)
- [Response Path](#response-path)
- [Spring Cloud Components at Each Step](#spring-cloud-components)
- [Error Propagation](#error-propagation)
- [Correlation ID Tracking](#correlation-id)

---

## Complete Request Journey

```
┌──────────┐     ┌─────────────┐     ┌─────────────────┐     ┌────────────────┐
│  Client  │────▶│ API Gateway │────▶│ Payment Service │────▶│Routing Service │
│(Merchant)│     │  :8080      │     │    :8083        │     │   :8085        │
└──────────┘     └─────────────┘     └─────────────────┘     └────────────────┘
                       │                      │                       │
                       │                      │                       │
                 ┌─────▼─────┐          ┌─────▼─────┐          ┌─────▼─────┐
                 │   Redis   │          │PostgreSQL │          │   Bank    │
                 │Rate Limit │          │ Payments  │          │Simulator  │
                 │   :6379   │          │   :5432   │          │  :9090    │
                 └───────────┘          └───────────┘          └───────────┘
                                              │
                                        ┌─────▼─────┐     ┌─────────────┐
                                        │   Kafka   │────▶│  Webhook    │
                                        │   :9092   │     │  Service    │
                                        └───────────┘     └─────────────┘
                                                                │
                                                          ┌─────▼─────┐
                                                          │ Merchant  │
                                                          │ Webhook   │
                                                          │ Endpoint  │
                                                          └───────────┘
```

**Full roundtrip time target:** < 500ms (P99)

---

## Step-by-Step: POST /v1/payments/authorize

### The Request

```http
POST /v1/payments/authorize HTTP/1.1
Host: api.payflow.com
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
X-API-Key: pk_live_abc123def456
X-Idempotency-Key: idem_order_789
X-Request-Id: req_550e8400-e29b
Content-Type: application/json

{
  "cardNumber": "4532015112830366",
  "expiryDate": "12/26",
  "cvv": "123",
  "amount": 100.00,
  "currency": "GBP",
  "merchantReference": "ORDER-789"
}
```

---

## Gateway Layer

**Spring Cloud Gateway** — the single entry point for all external requests.

```
Request arrives at :8080
         │
         ▼
┌─ RateLimitFilter ─────────────────────────────────┐
│  1. Extract client IP + API key                    │
│  2. Redis ZRANGEBYSCORE (sliding window check)     │
│  3. If limit exceeded → 429 Too Many Requests     │
│  4. Otherwise → pass to next filter               │
└────────────────────────────────────────────────────┘
         │
         ▼
┌─ JwtAuthenticationFilter ─────────────────────────┐
│  1. Extract Bearer token from Authorization header │
│  2. Validate signature (HMAC-SHA256)               │
│  3. Check expiration (must be < 15 min old)        │
│  4. Extract claims: userId, roles, merchantId      │
│  5. If invalid → 401 Unauthorized                  │
│  6. Add X-User-Id, X-Merchant-Id to downstream    │
└────────────────────────────────────────────────────┘
         │
         ▼
┌─ RouteLocator ────────────────────────────────────┐
│  Match path: /v1/payments/** → payment-service    │
│  Resolve via: Eureka service registry             │
│  Load balance: Spring Cloud LoadBalancer          │
│  Forward to: http://payment-service:8083          │
└────────────────────────────────────────────────────┘
```

**Key files:**
- `RateLimitFilter.java` — Redis-backed sliding window
- `JwtAuthenticationFilter.java` — Token validation + claim extraction
- `GatewayConfig.java` — Route definitions
- `application.yml` — Route predicates and filter chains

---

## Service Layer

**Payment Service** — orchestrates the payment lifecycle.

```
Request arrives at PaymentController
         │
         ▼
┌─ PaymentController ───────────────────────────────┐
│  @PostMapping("/v1/payments/authorize")            │
│  1. @Valid PaymentRequest → Jakarta validation     │
│  2. Extract merchantId from X-Merchant-Id header   │
│  3. Call paymentService.authorize(request)         │
└────────────────────────────────────────────────────┘
         │
         ▼
┌─ PaymentService ──────────────────────────────────┐
│  1. Check idempotency key in Redis                 │
│     → If found: return cached response (skip all)  │
│     → If new: acquire distributed lock (SETNX)     │
│  2. Create Payment entity (status = PENDING)       │
│  3. Save to PostgreSQL                             │
│  4. Call routingServiceClient.authorize(dto)       │
│  5. Update Payment (status = AUTHORIZED/DECLINED)  │
│  6. Store response in idempotency cache (24h TTL)  │
│  7. Publish PaymentEvent to Kafka                  │
│  8. Return PaymentResponse                         │
└────────────────────────────────────────────────────┘
         │
         ▼
┌─ PaymentRepository (JPA) ─────────────────────────┐
│  paymentRepository.save(payment)                   │
│  → PostgreSQL INSERT with generated UUID           │
│  → Automatic auditing: createdAt, updatedAt        │
└────────────────────────────────────────────────────┘
```

**Key files:**
- `PaymentController.java` — REST endpoint + validation
- `PaymentService.java` — Business logic orchestration
- `PaymentRepository.java` — Spring Data JPA interface
- `Payment.java` — JPA entity with state machine

---

## Inter-Service Communication

**Payment Service → Routing Service** via OpenFeign.

```
PaymentService calls RoutingServiceClient
         │
         ▼
┌─ RoutingServiceClient (Feign) ────────────────────┐
│  @FeignClient(name = "routing-service")            │
│  POST /internal/route/authorize                    │
│  → Eureka resolves "routing-service" to host:port  │
│  → Spring Cloud LoadBalancer picks instance        │
│  → Resilience4j circuit breaker wraps the call    │
│  → Request forwarded with X-Request-Id header      │
└────────────────────────────────────────────────────┘
         │
         ▼
┌─ RoutingController (routing-service) ─────────────┐
│  @PostMapping("/internal/route/authorize")         │
│  1. Run fraud checks (velocity, amount, country)   │
│  2. Smart routing: select best bank                │
│     → Score = cost×0.3 + successRate×0.4 +        │
│              latency×0.3                           │
│  3. Build ISO 8583 message (Iso8583MessageBuilder) │
│  4. Send via Netty TCP client to selected bank     │
│  5. Parse ISO 8583 response                        │
│  6. Map response code to PayFlow status            │
│  7. Return RoutingResponse                         │
└────────────────────────────────────────────────────┘
         │
         ▼
┌─ Netty TCP Client ────────────────────────────────┐
│  1. Get connection from pool (or create new)       │
│  2. Write ISO 8583 bytes to channel                │
│  3. Await response (async with timeout: 5 sec)     │
│  4. Decode response bytes                          │
│  5. Return to connection pool                      │
│  6. If timeout → throw BankTimeoutException        │
└────────────────────────────────────────────────────┘
         │
         ▼
┌─ Bank Simulator (:9090) ──────────────────────────┐
│  1. Receive ISO 8583 bytes on Netty TCP server     │
│  2. Parse message (MTI, bitmap, fields)            │
│  3. Simulate: check card, balance, fraud rules     │
│  4. Build ISO 8583 response (MTI 0110)             │
│  5. Set response code (00, 05, 51, etc.)           │
│  6. Send response bytes back on same connection    │
└────────────────────────────────────────────────────┘
```

---

## Async Events

**Payment Service → Kafka → Webhook Service** (fire-and-forget from payment path).

```
PaymentService publishes event after authorization
         │
         ▼
┌─ KafkaProducer (payment-service) ─────────────────┐
│  Topic: "payment.events"                           │
│  Key: paymentId (ensures ordering per payment)     │
│  Value: PaymentEvent (JSON serialized)             │
│  {                                                 │
│    "paymentId": "pay_abc123",                      │
│    "type": "PAYMENT_AUTHORIZED",                   │
│    "merchantId": "m_xyz789",                       │
│    "amount": 100.00,                               │
│    "currency": "GBP",                              │
│    "timestamp": "2024-01-15T14:30:52Z"             │
│  }                                                 │
└────────────────────────────────────────────────────┘
         │
         ▼  (async — does NOT block payment response)
┌─ WebhookConsumer (webhook-service) ───────────────┐
│  @KafkaListener(topics = "payment.events")         │
│  1. Deserialize PaymentEvent                       │
│  2. Look up merchant webhook URL from DB           │
│  3. Build webhook payload                          │
│  4. Sign with HMAC-SHA256 (merchant's secret)      │
│  5. POST to merchant's webhook URL                 │
│  6. If 2xx → mark delivered                        │
│  7. If failure → schedule retry (exponential)      │
│     Retry schedule: 1m, 5m, 30m, 2h, 24h          │
│  8. After max retries → send to dead letter queue  │
└────────────────────────────────────────────────────┘
```

---

## Response Path

The synchronous response flows back through every layer:

```
Bank Simulator
  └─ ISO 8583 response (MTI 0110, responseCode "00")
       └─ Netty TCP Client (decode bytes → RoutingResponse)
            └─ RoutingController (map to PayFlow status)
                 └─ Feign Client (HTTP 200 + JSON body)
                      └─ PaymentService (update entity, cache response)
                           └─ PaymentController (return PaymentResponse)
                                └─ API Gateway (pass through)
                                     └─ Client receives HTTP 200

Final response:
{
  "paymentId": "pay_abc123",
  "status": "AUTHORIZED",
  "authorizationCode": "AUTH01",
  "amount": 100.00,
  "currency": "GBP",
  "merchantReference": "ORDER-789",
  "createdAt": "2024-01-15T14:30:52Z"
}
```

---

## Spring Cloud Components at Each Step

| Step | Component | Role in PayFlow |
|------|-----------|-----------------|
| Service Registration | **Eureka Server** | All services register on startup. Gateway discovers services by name. |
| Configuration | **Spring Cloud Config** | Centralized config (application.yml per profile). Git-backed. |
| API Routing | **Spring Cloud Gateway** | Path-based routing, filter chains, rate limiting. |
| Service Discovery | **Eureka Client** | Each service resolves others by logical name, not IP. |
| Client Load Balancing | **Spring Cloud LoadBalancer** | Round-robin across healthy instances of target service. |
| Inter-Service HTTP | **OpenFeign** | Declarative HTTP clients with integrated discovery + LB. |
| Fault Tolerance | **Resilience4j** | Circuit breaker, rate limiter, retry, bulkhead per Feign client. |
| Async Messaging | **Spring Kafka** | Event publishing (producer) and consumption (consumer groups). |
| Distributed Config | **Config Server** | Environment-specific secrets and feature flags. |
| Health Monitoring | **Spring Boot Actuator** | /health, /metrics, /circuitbreakers endpoints. |

---

## Error Propagation

How errors flow back from bank to client:

```
BANK ERROR (e.g., timeout or response code 96):
  Bank Simulator → no response / error response
    │
    ▼
  Netty Client → BankTimeoutException / BankErrorException
    │
    ▼
  RoutingService → catches exception
    → If circuit CLOSED: increment failure count
    → If threshold reached: circuit → OPEN
    → Wrap in RoutingException with error details
    │
    ▼
  Feign Client (payment-service) → receives HTTP 502/503
    → Resilience4j: if circuit OPEN → fallback immediately
    → Maps to PaymentProcessingException
    │
    ▼
  PaymentService → catches RoutingException
    → Update payment status: FAILED
    → Set failure reason: "BANK_UNAVAILABLE" or "BANK_TIMEOUT"
    → Publish failure event to Kafka
    │
    ▼
  PaymentController → @ExceptionHandler maps to response
    → HTTP 502: { "error": "BANK_UNAVAILABLE", "message": "..." }
    │
    ▼
  API Gateway → passes error response through
    → Adds X-Request-Id header for tracing
    │
    ▼
  Client receives:
  {
    "error": "BANK_UNAVAILABLE",
    "message": "The payment processor is temporarily unavailable. Please retry.",
    "requestId": "req_550e8400-e29b",
    "retryable": true
  }
```

**Error mapping table:**

| Source Error | HTTP Status | Client Error Code | Retryable |
|-------------|-------------|-------------------|-----------|
| Bank timeout (5s) | 504 | `BANK_TIMEOUT` | Yes |
| Bank response 91 | 502 | `BANK_UNAVAILABLE` | Yes |
| Bank response 96 | 502 | `SYSTEM_ERROR` | Yes |
| Bank response 51 | 200 | `INSUFFICIENT_FUNDS` | No |
| Bank response 54 | 200 | `CARD_EXPIRED` | No |
| Circuit breaker OPEN | 503 | `SERVICE_UNAVAILABLE` | Yes (after wait) |
| Validation failure | 400 | `INVALID_REQUEST` | No (fix request) |
| Auth failure | 401 | `UNAUTHORIZED` | No (refresh token) |
| Rate limit exceeded | 429 | `RATE_LIMITED` | Yes (after reset) |

---

## Correlation ID (X-Request-Id) Tracking

Every request gets a unique correlation ID that flows through all services:

```
Client sends: X-Request-Id: req_550e8400-e29b
  (or Gateway generates one if missing)

┌─────────────────────────────────────────────────────────────┐
│ API Gateway                                                  │
│   → Log: [req_550e8400-e29b] Rate limit check passed        │
│   → Forward header to downstream                             │
├─────────────────────────────────────────────────────────────┤
│ Payment Service                                              │
│   → MDC.put("requestId", "req_550e8400-e29b")               │
│   → Log: [req_550e8400-e29b] Processing authorization       │
│   → Feign propagates header automatically                    │
├─────────────────────────────────────────────────────────────┤
│ Routing Service                                              │
│   → Log: [req_550e8400-e29b] Routing to BANK_A              │
│   → Log: [req_550e8400-e29b] ISO 8583 sent, trace=000042   │
├─────────────────────────────────────────────────────────────┤
│ Kafka Event                                                  │
│   → Header: X-Request-Id = req_550e8400-e29b                │
│   → Webhook service logs with same correlation ID            │
└─────────────────────────────────────────────────────────────┘
```

**Implementation:**
```java
// RequestIdFilter.java (in each service)
@Component
public class RequestIdFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        String requestId = ((HttpServletRequest) req).getHeader("X-Request-Id");
        if (requestId == null) {
            requestId = "req_" + UUID.randomUUID().toString().substring(0, 12);
        }
        MDC.put("requestId", requestId);
        ((HttpServletResponse) res).setHeader("X-Request-Id", requestId);
        chain.doFilter(req, res);
        MDC.clear();
    }
}
```

**Log output (searchable by single ID across all services):**
```
[payment-service] [req_550e8400-e29b] Received authorization request for merchant m_xyz789
[payment-service] [req_550e8400-e29b] Idempotency key not found, processing new request
[routing-service] [req_550e8400-e29b] Smart routing selected BANK_A (score: 0.87)
[routing-service] [req_550e8400-e29b] ISO 8583 auth request sent, trace=000042
[routing-service] [req_550e8400-e29b] Bank response received in 145ms, code=00
[payment-service] [req_550e8400-e29b] Payment pay_abc123 authorized, publishing event
[webhook-service] [req_550e8400-e29b] Webhook delivered to merchant in 89ms
```

---

*This document traces the complete code path for a payment authorization. For implementation details of each component, see the corresponding Phase 4 documentation.*
