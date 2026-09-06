# 🏗️ Phase 4 Part 8k: Payment Service — How Everything Connects

> **"45 files, 6 technologies, 4 external services. This document shows you the building — not the bricks."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 8k — Connections, Flows & Big Picture |
| **Previous** | [Part 8j — Controllers + Docker](./phase4-part08j-payment-controllers-docker.md) |
| **Next** | [Phase 4 Part 9 — Settlement Service](./phase4-part09-settlement-service.md) |

---

## 📖 Table of Contents

1. [How the 45 Files Connect to Each Other](#1-how-the-45-files-connect-to-each-other)
2. [Payment Lifecycle — End to End Through Every File](#2-payment-lifecycle--end-to-end-through-every-file)
3. [How Payment Service Connects to Infrastructure](#3-how-payment-service-connects-to-infrastructure)
4. [How Payment Service Connects to Other Microservices](#4-how-payment-service-connects-to-other-microservices)
5. [The Complete Payment Timeline](#5-the-complete-payment-timeline)
6. [What Would Break If You Removed Each File](#6-what-would-break-if-you-removed-each-file)
7. [Summary — The Complete Mental Model](#7-summary--the-complete-mental-model)

---

## 1. How the 45 Files Connect to Each Other

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    PAYMENT SERVICE — INTERNAL WIRING                             │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  pom.xml ──────► ALL FILES (libraries: JPA, Redis, Kafka, Feign, Validation)   │
│  application.yml ► Startup (DB, Redis, Kafka, Eureka, HikariCP, Feign logging) │
│  PaymentServiceApplication ► @EnableFeignClients + Component Scan              │
│  SecurityConfig ► /v1/**, /internal/**, /actuator/** permitAll                 │
│                                                                                 │
│  CONTROLLERS (HTTP entry) ─────────────► SERVICES (logic)                      │
│  ┌──────────────────┐                   ┌──────────────────┐                   │
│  │ OrderController   │──────────────────►│ OrderService     │                   │
│  │ /v1/orders        │                   └──────┬───────────┘                   │
│  ├──────────────────┤                          │                               │
│  │ PaymentController │──► IdempotencyService ──►│                               │
│  │ /v1/payments      │──────────────────────────►│ PaymentService               │
│  │ + Idempotency-Key │                   ┌──────┤  (CORE ENGINE)               │
│  ├──────────────────┤                   │      └──────┬───────────┘           │
│  │ RefundController  │──────────────────►│ RefundService│                       │
│  │ /v1/refunds       │                   └──────┬──────┘                       │
│  └──────────────────┘                          │                               │
│                                                 ▼                               │
│  SERVICES use: ─────────────────────────────────────────────────               │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐            │
│  │ Repositories (4) │  │ EventPublisher   │  │ RoutingService   │            │
│  │ Order, Payment,  │  │ → KafkaPublisher │  │ Client (Feign)   │            │
│  │ PaymentMethod,   │  │ → SqsPublisher   │  └────────┬─────────┘            │
│  │ Refund           │  └────────┬─────────┘           │                       │
│  └────────┬─────────┘           │                     │                       │
│           │                     ▼                     ▼                       │
│      PostgreSQL            Kafka (:9092)        routing-service              │
│      (:5432)               (async events)       (bank authorization)          │
│                                                                                 │
│  IdempotencyService ──► Redis (:6379) (SET NX EX pattern)                     │
│                                                                                 │
│  MAPPERS convert: Entity ←──► DTO                                              │
│  ┌──────────────────┐  ┌──────────────────┐                                   │
│  │ OrderMapper      │  │ PaymentMapper    │                                   │
│  │ Order→OrderResp  │  │ Payment→PayResp  │                                   │
│  └──────────────────┘  └──────────────────┘                                   │
│                                                                                 │
│  EXCEPTION HANDLER catches: all controller/service exceptions → JSON           │
│  PaymentExceptionHandler: 404, 402, 409, 422, 400, 500                        │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Payment Lifecycle — End to End Through Every File

### A Complete Payment: Create Order → Authorize → Capture → Refund

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│ T0: MERCHANT CREATES ORDER                                                     │
│ ═══════════════════════════                                                     │
│                                                                                 │
│ Client → POST /v1/orders                                                       │
│                                                                                 │
│ FILES TOUCHED:                                                                 │
│ 1. SecurityConfig        → permitAll /v1/** ✓                                 │
│ 2. OrderController       → @PostMapping matches                               │
│ 3. CreateOrderRequest    → Jackson deserializes, @Valid validates              │
│ 4. OrderService          → IdGenerator.generateOrderId() → "order_abc"        │
│                          → Instant.now().plus(30, MINUTES) → expiresAt        │
│                          → status = CREATED                                    │
│ 5. OrderRepository       → save() → INSERT INTO orders                        │
│ 6. Order entity          → Hibernate maps to table                            │
│ 7. V1 migration          → table must exist                                   │
│ 8. OrderMapper           → toResponse() → enum→String                        │
│ 9. OrderResponse         → Jackson serializes to JSON                         │
│                                                                                 │
│ Result: { "id": "order_abc", "status": "CREATED", "expiresAt": "..." }        │
│                                                                                 │
├─────────────────────────────────────────────────────────────────────────────────┤
│ T1: CUSTOMER AUTHORIZES PAYMENT (with card)                                    │
│ ═══════════════════════════════════════════                                      │
│                                                                                 │
│ Client → POST /v1/payments/authorize                                           │
│          Idempotency-Key: uuid-123                                             │
│          {"orderId":"order_abc","paymentMethod":"CARD","cardNumber":"4111..."}  │
│                                                                                 │
│ FILES TOUCHED (in order):                                                      │
│  1. SecurityConfig            → permitAll ✓                                   │
│  2. PaymentController         → @PostMapping("/authorize")                    │
│  3. AuthorizePaymentRequest   → Jackson + @Valid                              │
│  4. IdempotencyService        → getCachedResponse("uuid-123")                 │
│  5. RedisConfig               → StringRedisTemplate connects to Redis         │
│     → Redis GET idempotency:uuid-123 → null (first time)                     │
│  6. IdempotencyService        → acquireLock("uuid-123")                       │
│     → Redis SET idempotency:uuid-123 "PROCESSING" NX EX 86400               │
│  7. PaymentService.authorize()                                                │
│  8.   OrderService            → getOrderEntity("order_abc")                   │
│  9.   OrderRepository         → findById → SELECT FROM orders                │
│ 10.   Order entity            → validates status, expiresAt                   │
│ 11.   PaymentRepository       → findByOrderId → no existing payment          │
│ 12.   Payment entity          → builder with IdGenerator.generatePaymentId()  │
│ 13.   PaymentRepository       → save() → INSERT INTO payments                │
│ 14.   PaymentMethodEntity     → builder with cardLast4="1111", brand="VISA"  │
│ 15.   PaymentMethodRepository → save() → INSERT INTO payment_methods         │
│ 16.   OrderService            → updateOrderStatus(ATTEMPTED)                  │
│ 17.   RoutingServiceClient    → Feign POST to routing-service/internal/route │
│ 18.   FeignConfig             → 5s connect, 10s read, 3 retries             │
│        → routing-service → bank → response: AUTHORIZED                       │
│ 19.   Payment entity          → status=AUTHORIZED, authorizationCode set     │
│ 20.   PaymentRepository       → save() → UPDATE payments SET status=...      │
│ 21.   EventPublisher          → publishPaymentEvent("payment.authorized")    │
│ 22.   KafkaEventPublisher     → kafkaTemplate.send() → Kafka                │
│ 23.   KafkaProducerConfig     → acks=all, idempotence=true                  │
│ 24.   PaymentMapper           → toResponse() → enum→String                  │
│ 25.   PaymentResponse         → JSON output                                  │
│ 26.   IdempotencyService      → cacheResponse("uuid-123", response)         │
│        → Redis SET idempotency:uuid-123 '{"id":"pay_xyz",...}' EX 86400    │
│ 27.   PaymentController       → 201 Created + ApiResponse.success()          │
│                                                                                 │
│ = 27 files touched for ONE authorize request                                   │
│                                                                                 │
├─────────────────────────────────────────────────────────────────────────────────┤
│ T2: MERCHANT CAPTURES PAYMENT                                                  │
│ ═════════════════════════════                                                   │
│                                                                                 │
│ Client → POST /v1/payments/capture {"paymentId":"pay_xyz","amount":1500.00}   │
│                                                                                 │
│ FILES TOUCHED:                                                                 │
│ 1. PaymentController    → capture()                                           │
│ 2. CapturePaymentRequest → @Valid                                             │
│ 3. PaymentService.capture()                                                    │
│ 4.   PaymentRepository  → findById("pay_xyz") → AUTHORIZED ✓                │
│ 5.   State validation   → status==AUTHORIZED? ✓                              │
│ 6.   Amount validation  → 1500 ≤ authorized amount? ✓                       │
│ 7.   Payment entity     → status=CAPTURED, amount=1500                        │
│ 8.   PaymentRepository  → save() → UPDATE                                    │
│ 9.   OrderService       → updateOrderStatus("order_abc", PAID)               │
│ 10.  EventPublisher     → "payment.captured" → Kafka                         │
│ 11.  PaymentMapper      → toResponse()                                        │
│                                                                                 │
├─────────────────────────────────────────────────────────────────────────────────┤
│ T3: CUSTOMER REQUESTS PARTIAL REFUND                                           │
│ ════════════════════════════════════                                             │
│                                                                                 │
│ Client → POST /v1/refunds {"paymentId":"pay_xyz","amount":500.00}             │
│                                                                                 │
│ FILES TOUCHED:                                                                 │
│ 1. RefundController     → createRefund()                                      │
│ 2. RefundRequest        → @Valid                                               │
│ 3. RefundService.createRefund()                                                │
│ 4.   PaymentRepository  → findById → CAPTURED ✓                              │
│ 5.   RefundRepository   → findByPaymentId → [] (no prior refunds)            │
│ 6.   BigDecimal math    → totalRefunded=0, remaining=1500, 500≤1500 ✓       │
│ 7.   Refund entity      → IdGenerator.generateRefundId() → "rfnd_abc"        │
│ 8.   RefundRepository   → save() → INSERT                                    │
│ 9.   Check full refund  → 500 < 1500 → payment stays CAPTURED               │
│ 10.  EventPublisher     → "payment.refunded" → Kafka                         │
│ 11.  RefundResponse     → manual builder                                      │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. How Payment Service Connects to Infrastructure

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  PAYMENT SERVICE (Port 8083)                                                   │
│       │          │          │          │          │                              │
│       ▼          ▼          ▼          ▼          ▼                              │
│                                                                                 │
│  PostgreSQL   Redis      Kafka     Eureka     Config                           │
│  :5432        :6379      :9092     :8761      Server                           │
│                                               :8888                             │
│                                                                                 │
│  POSTGRESQL (payflow_payment)                                                  │
│  ──────────────────────────────                                                │
│  WHAT: Permanent storage — orders, payments, methods, refunds                  │
│  FILES: application.yml (connection), Flyway V1-V4 (tables),                  │
│         Repositories (queries), Entities (mapping), HikariCP (pool)           │
│  TABLES: orders (4 indexes), payments (4 indexes),                            │
│          payment_methods (1 index), refunds (2 indexes)                       │
│                                                                                 │
│  REDIS (Idempotency Cache)                                                     │
│  ──────────────────────────                                                    │
│  WHAT: Prevents double charges — 24-hour key-value cache                       │
│  FILES: RedisConfig (StringRedisTemplate), IdempotencyService (SET NX EX),    │
│         PaymentController (check/lock/cache/release)                          │
│  DATA: "idempotency:uuid-123" → "PROCESSING" or '{"paymentId":"pay_abc",...}'│
│  IF DOWN: Payments process normally (without idempotency protection)           │
│                                                                                 │
│  KAFKA (Event Publishing)                                                      │
│  ────────────────────────                                                      │
│  WHAT: Publishes payment lifecycle events for downstream services              │
│  FILES: KafkaProducerConfig, KafkaEventPublisher, EventPublisher interface    │
│  TOPICS: payment.authorized, payment.captured, payment.failed, payment.refunded│
│  CONSUMERS: Webhook Service, Settlement Service, Notification Service         │
│  IF DOWN: Events lost (logged), payment still succeeds (fire-and-forget)      │
│                                                                                 │
│  EUREKA (Service Registry)                                                     │
│  ────────────────────────                                                      │
│  WHAT: Payment registers itself + looks up routing-service/merchant-service   │
│  FILES: application.yml (eureka config), Feign clients (name-based lookup)    │
│  CRITICAL: fetch-registry=true (must find other services for Feign calls)     │
│                                                                                 │
│  API GATEWAY (:8080)                                                           │
│  ────────────────────                                                          │
│  WHAT: Routes /v1/orders/**, /v1/payments/**, /v1/refunds/** to this service │
│  ADDS: X-Request-Id, X-User-Id (from JWT), X-API-Key passthrough            │
│  BLOCKS: Rate-limited requests, invalid JWTs                                  │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. How Payment Service Connects to Other Microservices

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  ┌───────────────────────────────────────────────────────────────┐             │
│  │ ROUTING SERVICE (Port 8084) — BANK AUTHORIZATION              │             │
│  │                                                               │             │
│  │ HOW: PaymentService → RoutingServiceClient (Feign)           │             │
│  │      POST /internal/route {paymentId, amount, currency, ...} │             │
│  │ RETURNS: {status:"AUTHORIZED", authorizationCode:"AUTH123"}  │             │
│  │      or: {status:"DECLINED", reason:"Insufficient funds"}    │             │
│  │                                                               │             │
│  │ RoutingService → selects bank → forwards to bank/simulator   │             │
│  │                                                               │             │
│  │ FILES: RoutingServiceClient.java, FeignConfig.java           │             │
│  │ IF DOWN: Payment FAILS with "ROUTING_ERROR" (Feign retries 3x)│            │
│  └───────────────────────────────────────────────────────────────┘             │
│                                                                                 │
│  ┌───────────────────────────────────────────────────────────────┐             │
│  │ MERCHANT SERVICE (Port 8082) — MERCHANT VALIDATION            │             │
│  │                                                               │             │
│  │ HOW: MerchantServiceClient (Feign) — defined but NOT used    │             │
│  │      Merchant validation currently happens at API Gateway    │             │
│  │      (via X-API-Key → merchant-service/api-keys/validate)   │             │
│  │                                                               │             │
│  │ FUTURE: Direct Feign call for merchant config lookups        │             │
│  │ FILES: MerchantServiceClient.java                            │             │
│  └───────────────────────────────────────────────────────────────┘             │
│                                                                                 │
│  ┌───────────────────────────────────────────────────────────────┐             │
│  │ IDENTITY SERVICE (Port 8081) — USER AUTHENTICATION            │             │
│  │                                                               │             │
│  │ HOW: Payment does NOT call Identity directly                 │             │
│  │      JWT validation happens at API Gateway                   │             │
│  │      Gateway passes X-User-Id/X-User-Role headers           │             │
│  │ DEPENDENCY: Indirect (via JWT tokens issued by Identity)     │             │
│  └───────────────────────────────────────────────────────────────┘             │
│                                                                                 │
│  ┌───────────────────────────────────────────────────────────────┐             │
│  │ WEBHOOK SERVICE (Port 8087) — EVENT CONSUMER                  │             │
│  │                                                               │             │
│  │ HOW: Reads Kafka topics: payment.authorized, payment.captured│             │
│  │      Looks up merchant's webhook config                      │             │
│  │      Signs payload with HMAC + delivers to merchant's URL    │             │
│  │                                                               │             │
│  │ DEPENDENCY: Kafka events published by PaymentService/RefundService│         │
│  │ FILES: EventPublisher → KafkaEventPublisher                  │             │
│  └───────────────────────────────────────────────────────────────┘             │
│                                                                                 │
│  ┌───────────────────────────────────────────────────────────────┐             │
│  │ SETTLEMENT SERVICE (Port 8085) — FEE CALCULATION              │             │
│  │                                                               │             │
│  │ HOW: Reads Kafka topic: payment.captured                     │             │
│  │      Reads merchant's FeeConfig (MDR + GST percentages)      │             │
│  │      Calculates: amount - MDR - GST = merchant payout        │             │
│  │                                                               │             │
│  │ DEPENDENCY: Kafka events + payflow_merchant DB (FeeConfig)   │             │
│  └───────────────────────────────────────────────────────────────┘             │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. The Complete Payment Timeline

```
TIME ──────────────────────────────────────────────────────────────────────────►

T0: MERCHANT CREATES ORDER
    Merchant Portal → Gateway → Payment Service
    POST /v1/orders {amount:1500, currency:"INR"}
    → Order: CREATED, expires in 30 min
    → order_abc123

T1: CUSTOMER AUTHORIZES (2 seconds later)
    Checkout Page → Gateway → Payment Service
    POST /v1/payments/authorize {orderId:order_abc123, paymentMethod:CARD, ...}
    Idempotency-Key: unique-uuid
    → Redis: SET idempotency:unique-uuid "PROCESSING" NX EX 86400
    → Payment: CREATED → calls routing-service → bank approves
    → Payment: AUTHORIZED (authCode: AUTH847)
    → Order: ATTEMPTED
    → Kafka: payment.authorized event published
    → Redis: SET idempotency:unique-uuid '{"id":"pay_xyz",...}'
    → pay_xyz789

T2: WEBHOOK DELIVERED (5 seconds later)
    Kafka → Webhook Service → Merchant's URL
    POST https://merchant-site.com/payflow-webhook
    X-Payflow-Signature: sha256=abc...
    {"event":"payment.authorized","paymentId":"pay_xyz789","amount":1500}

T3: MERCHANT CAPTURES (1 hour later)
    Merchant Portal → Gateway → Payment Service
    POST /v1/payments/capture {paymentId:pay_xyz789}
    → Payment: AUTHORIZED → CAPTURED
    → Order: ATTEMPTED → PAID
    → Kafka: payment.captured event published

T4: SETTLEMENT (next day)
    Kafka → Settlement Service
    → Reads FeeConfig: MDR=2%, GST=18%
    → MDR = 1500 × 2% = ₹30, GST = 30 × 18% = ₹5.40
    → Merchant receives: ₹1500 - ₹30 - ₹5.40 = ₹1,464.60

T5: CUSTOMER RETURNS ITEM (3 days later)
    Merchant Portal → Gateway → Payment Service
    POST /v1/refunds {paymentId:pay_xyz789, amount:500, reason:"Return"}
    → Refund: rfnd_abc, amount=500, status=PROCESSED
    → Payment stays CAPTURED (partial refund, ₹1000 still captured)
    → Kafka: payment.refunded event published

T6: ORDER EXPIRY (for unpaid orders)
    Cron job / Admin → POST /v1/orders/expire
    → All CREATED orders where expiresAt < now → EXPIRED
```

---

## 6. What Would Break If You Removed Each File

### Infrastructure Files

| Remove | What Breaks | Error |
|---|---|---|
| `pom.xml` | Everything | Build fails |
| `application.yml` | Startup | "Failed to configure DataSource" |
| `PaymentServiceApplication` | Startup | No main class |
| `SecurityConfig` | All requests | 401 Unauthorized |

### Entities + Migrations

| Remove | What Breaks | Error |
|---|---|---|
| `Order.java` | OrderRepository | "Not a managed type" |
| `V1 migration` | Startup | "Table 'orders' doesn't exist" |
| `Payment.java` | PaymentRepository | "Not a managed type" |
| `PaymentMethodEntity` | PaymentMethodRepository | "Not a managed type" |
| `Refund.java` | RefundRepository | "Not a managed type" |

### Repositories

| Remove | What Breaks | Error |
|---|---|---|
| `OrderRepository` | OrderService | "No qualifying bean" |
| `PaymentRepository` | PaymentService | "No qualifying bean" |
| `PaymentMethodRepository` | PaymentService | "No qualifying bean" |
| `RefundRepository` | RefundService | "No qualifying bean" |

### Config Classes

| Remove | What Breaks | Error |
|---|---|---|
| `RedisConfig` | IdempotencyService | Default serializer (binary keys in Redis) |
| `KafkaProducerConfig` | KafkaEventPublisher | No KafkaTemplate bean (if auto-config doesn't suffice) |
| `FeignConfig` | Feign clients | Default timeouts (10s/60s — too slow) |

### Services

| Remove | What Breaks | Error |
|---|---|---|
| `OrderService` | OrderController + PaymentService | "No qualifying bean" |
| `PaymentService` | PaymentController | "No qualifying bean" |
| `RefundService` | RefundController | "No qualifying bean" |
| `IdempotencyService` | PaymentController | "No qualifying bean" |
| `EventPublisher` | PaymentService + RefundService | "No qualifying bean" |
| `KafkaEventPublisher` | Events not published | No EventPublisher implementation (if !aws profile) |

### Controllers + Feign

| Remove | What Breaks | Error |
|---|---|---|
| `OrderController` | No /v1/orders endpoints | 404 on all order URLs |
| `PaymentController` | No /v1/payments endpoints | 404 |
| `RefundController` | No /v1/refunds endpoints | 404 |
| `PaymentExceptionHandler` | Ugly error responses | HTML instead of JSON |
| `RoutingServiceClient` | Bank authorization | "No qualifying bean" in PaymentService |
| `Dockerfile` | Can't containerize | Still works locally |

---

## 7. Summary — The Complete Mental Model

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  THE PAYMENT SERVICE IN ONE PICTURE:                                           │
│                                                                                 │
│  WHAT IT IS:                                                                   │
│    The core engine of PayFlow. Processes every payment.                        │
│    Manages: Orders → Payments → Refunds                                       │
│                                                                                 │
│  6 TECHNOLOGIES IT USES:                                                       │
│    PostgreSQL (permanent data) + Redis (idempotency cache) +                  │
│    Kafka (event publishing) + Feign (inter-service HTTP) +                    │
│    HikariCP (connection pooling) + MapStruct (DTO mapping)                    │
│                                                                                 │
│  WHAT IT DEPENDS ON (built before it):                                         │
│    common-lib     → ApiResponse, exceptions, enums, IdGenerator, PaymentEvent │
│    Eureka         → service registration + Feign service lookup              │
│    Config Server  → centralized configuration (optional locally)             │
│    API Gateway    → routes /v1/orders/**, /v1/payments/**, /v1/refunds/**    │
│    Infrastructure → PostgreSQL, Redis, Kafka (via docker-compose)            │
│                                                                                 │
│  WHAT DEPENDS ON IT (built after / alongside):                                │
│    Routing Service  → called via Feign for bank authorization               │
│    Webhook Service  → consumes Kafka events → delivers to merchant URLs     │
│    Settlement Service → consumes Kafka events → calculates merchant payouts │
│    Notification Service → consumes Kafka events → sends emails/SMS          │
│                                                                                 │
│  KEY PATTERNS:                                                                 │
│    State Machine   → Order: CREATED→ATTEMPTED→PAID/EXPIRED                   │
│                    → Payment: CREATED→AUTHORIZED→CAPTURED/VOIDED/FAILED      │
│    Idempotency    → Redis SET NX EX prevents double charges                  │
│    Fire-and-Forget → Kafka event failures don't break payments              │
│    Feign + Eureka → Call services by name, not hardcoded URLs               │
│    Two-Phase Pay  → Authorize (reserve) → Capture (collect)                 │
│                                                                                 │
│  NUMBERS:                                                                      │
│    45 source files + 4 SQL migrations + 4 test files                         │
│    11 endpoints (4 orders + 4 payments + 3 refunds)                          │
│    4 database tables with 11 indexes                                         │
│    6 exception types mapped to HTTP 400/402/404/409/422/500                  │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 🎉 Payment Service Complete!

You've built the most complex service in PayFlow across 11 parts:

| Part | What You Built | Files |
|---|---|---|
| **8a** | Project Setup | pom.xml, yml, main, security (4) |
| **8b** | Entities | Order, Payment, PaymentMethod, Refund (4) |
| **8c** | Migrations | V1-V4 SQL with partial + composite indexes (4) |
| **8d** | Repositories | 4 data access interfaces (4) |
| **8e** | DTOs + Mappers | 7 DTOs + 2 MapStruct mappers (9) |
| **8f** | Config Classes | Redis, Kafka, Feign configs (3) |
| **8g** | Order + Refund Services | 2 services + 1 test (3) |
| **8h** | Idempotency + Events | Interface + 2 publishers + service + test (5) |
| **8i** | Payment Engine | Core PaymentService + test (2) |
| **8j** | Controllers + Docker | 3 controllers + handler + 2 feign + test + Dockerfile (8) |
| **8k** | Connections | This document — big picture |

**Total: 45 files. 3 new technologies (Redis, Kafka, Feign). The heart of PayFlow.**

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
| [Part 8j](./phase4-part08j-payment-controllers-docker.md) | Controllers + Docker |
| **Part 8k** | **How Everything Connects** (You are here) |

---

*Payment Service is COMPLETE. Next: [Phase 4 Part 9 — Settlement Service](./phase4-part09-settlement-service.md) →*
