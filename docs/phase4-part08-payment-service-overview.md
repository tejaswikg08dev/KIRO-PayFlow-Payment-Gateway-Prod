# 🏗️ Phase 4 Part 08: Payment Service — Overview & Roadmap

> **"The Payment Service is the heart of PayFlow. Every rupee flows through here."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 8 (split into 8a through 8k) |
| **Module** | `payment-service` |
| **Package** | `com.payflow.payment` |
| **Port** | 8083 |
| **Database** | PostgreSQL — `payflow_payment` |
| **Also Uses** | Redis (idempotency), Kafka (events), Feign (inter-service calls) |
| **Previous** | [Phase 4 Part 7h — Merchant Connections](./phase4-part07h-merchant-connections-and-flows.md) |
| **Next** | [Phase 4 Part 9 — Routing Service](./phase4-part09-routing-service-overview.md) |

---

## 🎯 What Is the Payment Service?

The Payment Service is the **core engine** of PayFlow — it processes every payment that flows through the system. While Identity handles "who are you?" and Merchant handles "what business do you run?", Payment handles "take money from the customer and give it to the merchant."

### Real-World Analogy

Think of it like a POS (Point of Sale) machine at a shop:

| Step | Real World | PayFlow Equivalent |
|---|---|---|
| 1 | Customer brings items to counter | Merchant creates an **Order** (amount, currency) |
| 2 | Customer taps their card | Customer **authorizes** payment (card/UPI/net banking) |
| 3 | POS shows "Approved" | Bank **authorizes** → PayFlow stores authorization code |
| 4 | Shop charges the card | Merchant **captures** the payment (money moves) |
| 5 | Customer wants return | Merchant **refunds** the payment (money returns) |
| 6 | Receipt has same transaction ID | **Idempotency** ensures no double-charge |

---

## 🛠️ Tech Stack

### What's NEW Compared to Merchant Service

| Technology | Version | Purpose | NEW? |
|---|---|---|---|
| **Java** | 17 | Language | Same |
| **Spring Boot** | 3.2.5 | Framework | Same |
| **Spring Data JPA** | (via starter) | ORM | Same |
| **Spring Security** | (via starter) | Endpoint protection | Same |
| **Spring Validation** | (via starter) | Input validation | Same |
| **Spring Cloud Eureka** | 2023.0.1 | Service discovery | Same |
| **Spring Cloud Config** | 2023.0.1 | Centralized config | Same |
| **PostgreSQL** | 16 | Database | Same |
| **Flyway** | (via starter) | Migrations | Same |
| **Lombok** | 1.18.32 | Boilerplate reduction | Same |
| **MapStruct** | 1.5.5 | DTO mapping | Same |
| **SpringDoc** | 2.5.0 | Swagger UI | Same |
| **Spring Data Redis** | (via starter) | Idempotency cache | 🆕 **NEW** |
| **Spring Kafka** | (via starter) | Event publishing | 🆕 **NEW** |
| **Spring Cloud OpenFeign** | 2023.0.1 | Inter-service HTTP calls | 🆕 **NEW** |
| **HikariCP** | (via JPA starter) | DB connection pooling | 🆕 **NEW** (explicit config) |
| **Testcontainers** | 1.19.7 | PostgreSQL/Kafka in tests | 🆕 **NEW** |

**3 completely new technologies:** Redis, Kafka, Feign — each gets its own config class (Part 8f).

---

## 📐 What We Build — 15+ Endpoints

### Orders (4 endpoints)

| # | Method | Path | Description |
|---|---|---|---|
| 1 | POST | `/v1/orders` | Create a new order (merchant initiates) |
| 2 | GET | `/v1/orders/{orderId}` | Get order by ID |
| 3 | GET | `/v1/orders?merchantId=` | List orders by merchant |
| 4 | POST | `/v1/orders/expire` | Expire stale orders (admin/cron) |

### Payments (4 endpoints)

| # | Method | Path | Description |
|---|---|---|---|
| 5 | POST | `/v1/payments/authorize` | Authorize a payment (reserve money) |
| 6 | POST | `/v1/payments/capture` | Capture a payment (collect money) |
| 7 | POST | `/v1/payments/{paymentId}/void` | Void an authorization (cancel) |
| 8 | GET | `/v1/payments/{paymentId}` | Get payment by ID |

### Refunds (3 endpoints)

| # | Method | Path | Description |
|---|---|---|---|
| 9 | POST | `/v1/refunds` | Create a refund (full or partial) |
| 10 | GET | `/v1/refunds/{refundId}` | Get refund by ID |
| 11 | GET | `/v1/refunds?paymentId=` | List refunds by payment |

---

## 🔄 State Machines — The Core Concept

### Order State Machine

```
┌──────────┐    customer pays     ┌───────────┐    payment captured   ┌────────┐
│ CREATED  │ ───────────────────► │ ATTEMPTED │ ────────────────────► │  PAID  │
└──────────┘                      └───────────┘                       └────────┘
     │
     │ 30 minutes pass (TTL expires)
     ▼
┌──────────┐
│ EXPIRED  │
└──────────┘
```

| State | Meaning | How It Gets Here |
|---|---|---|
| `CREATED` | Order placed, waiting for payment | `POST /v1/orders` |
| `ATTEMPTED` | Payment authorization attempted | `POST /v1/payments/authorize` |
| `PAID` | Payment captured, money collected | `POST /v1/payments/capture` |
| `EXPIRED` | 30 minutes passed, no payment | `POST /v1/orders/expire` (cron/admin) |

### Payment State Machine

```
                                    ┌──────────┐
                              ┌────►│ CAPTURED │
                              │     └──────────┘
┌──────────┐   bank approves  │          │
│ CREATED  │ ───────────────► │          │ refund()
│          │                  │          ▼
└──────────┘             ┌────┴─────┐  ┌──────────┐
     │                   │AUTHORIZED│  │ REFUNDED │
     │                   └────┬─────┘  └──────────┘
     │ bank declines          │
     ▼                        │ void()
┌──────────┐                  ▼
│  FAILED  │             ┌──────────┐
└──────────┘             │  VOIDED  │
                         └──────────┘
```

| State | Meaning | How It Gets Here |
|---|---|---|
| `CREATED` | Payment record created, about to call bank | Internal (during authorize flow) |
| `AUTHORIZED` | Bank approved, money reserved on customer's card | Bank response → approved |
| `CAPTURED` | Money collected from customer's account | `POST /v1/payments/capture` |
| `VOIDED` | Authorization cancelled before capture | `POST /v1/payments/{id}/void` |
| `FAILED` | Bank declined the transaction | Bank response → declined |
| `REFUNDED` | Money returned to customer (after capture) | `POST /v1/refunds` (when fully refunded) |

---

## 🏗️ Architecture Diagram

```
┌───────────────────────────────────────────────────────────────────────────────────┐
│                       PAYMENT SERVICE (Port 8083)                                  │
├───────────────────────────────────────────────────────────────────────────────────┤
│                                                                                   │
│   HTTP Requests                                                                   │
│       │                                                                           │
│       ▼                                                                           │
│   ┌──────────────┐ ┌──────────────────┐ ┌────────────────┐                      │
│   │OrderController│ │PaymentController │ │RefundController│                      │
│   │  /v1/orders   │ │ /v1/payments     │ │ /v1/refunds    │                      │
│   └──────┬───────┘ └───────┬──────────┘ └───────┬────────┘                      │
│          │                 │                     │                                │
│          ▼                 ▼                     ▼                                │
│   ┌──────────┐   ┌────────────────┐   ┌──────────────┐                          │
│   │OrderService│  │PaymentService  │   │RefundService │                          │
│   │           │   │(CORE ENGINE)   │   │              │                          │
│   └─────┬────┘   └──┬─────┬──┬───┘   └──────┬───────┘                          │
│         │            │     │  │               │                                   │
│         │            │     │  │               │                                   │
│         ▼            ▼     │  ▼               ▼                                   │
│   ┌──────────────────────┐ │ ┌────────────────────┐                              │
│   │  Repositories (4)    │ │ │  EventPublisher     │                              │
│   │  Order, Payment,     │ │ │  → KafkaPublisher   │──────► Kafka (:9092)        │
│   │  PaymentMethod,      │ │ │    (async events)   │                              │
│   │  Refund              │ │ └────────────────────┘                              │
│   └──────────┬───────────┘ │                                                     │
│              │             │  ┌────────────────────┐                              │
│              ▼             │  │IdempotencyService  │──────► Redis (:6379)         │
│   PostgreSQL (:5432)       │  │ (SET NX EX)        │                              │
│   payflow_payment DB       │  └────────────────────┘                              │
│                            │                                                      │
│                            │  ┌────────────────────┐                              │
│                            └─►│RoutingServiceClient│──────► routing-service       │
│                               │(Feign HTTP call)   │        (bank authorization)  │
│                               └────────────────────┘                              │
│                                                                                   │
└───────────────────────────────────────────────────────────────────────────────────┘
```

---

## 📄 Sub-Parts — Build Order (8a → 8k)

```
┌──────────────────────────────────────────────────────────────────────────┐
│                    BUILD ORDER (8a → 8k)                                  │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  8a  Project Setup       pom.xml, application.yml, main class, security │
│   ▼                                                                      │
│  8b  Entities            Order, Payment, PaymentMethodEntity, Refund    │
│   ▼                                                                      │
│  8c  Flyway Migrations   V1-V4 SQL (tables + indexes)                   │
│   ▼                                                                      │
│  8d  Repositories        4 data access interfaces                       │
│   ▼                                                                      │
│  8e  DTOs + Mappers      7 DTOs + 2 MapStruct mappers                  │
│   ▼                                                                      │
│  8f  Config Classes      RedisConfig, KafkaProducerConfig, FeignConfig  │
│   ▼                                                                      │
│  8g  Order + Refund Svc  OrderService, RefundService + tests            │
│   ▼                                                                      │
│  8h  Idempotency + Events IdempotencyService, EventPublisher,           │
│   ▼                       KafkaEventPublisher, SqsEventPublisher + tests│
│  8i  Payment Engine      PaymentService (core) + tests                  │
│   ▼                                                                      │
│  8j  Controllers + Docker 3 controllers, exception handler,             │
│   ▼                       2 Feign clients, Dockerfile, curl tests       │
│  8k  Connections & Flows  How everything connects                       │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 🗂️ Complete File List (45 files)

```
backend/payment-service/
├── pom.xml                                                    ← 8a
├── Dockerfile                                                 ← 8j
└── src/
    ├── main/
    │   ├── java/com/payflow/payment/
    │   │   ├── PaymentServiceApplication.java                 ← 8a
    │   │   ├── config/
    │   │   │   ├── SecurityConfig.java                        ← 8a
    │   │   │   ├── RedisConfig.java                           ← 8f
    │   │   │   ├── KafkaProducerConfig.java                   ← 8f
    │   │   │   └── FeignConfig.java                           ← 8f
    │   │   ├── model/
    │   │   │   ├── Order.java                                 ← 8b
    │   │   │   ├── Payment.java                               ← 8b
    │   │   │   ├── PaymentMethodEntity.java                   ← 8b
    │   │   │   └── Refund.java                                ← 8b
    │   │   ├── repository/
    │   │   │   ├── OrderRepository.java                       ← 8d
    │   │   │   ├── PaymentRepository.java                     ← 8d
    │   │   │   ├── PaymentMethodRepository.java               ← 8d
    │   │   │   └── RefundRepository.java                      ← 8d
    │   │   ├── dto/
    │   │   │   ├── CreateOrderRequest.java                    ← 8e
    │   │   │   ├── AuthorizePaymentRequest.java               ← 8e
    │   │   │   ├── CapturePaymentRequest.java                 ← 8e
    │   │   │   ├── RefundRequest.java                         ← 8e
    │   │   │   ├── OrderResponse.java                         ← 8e
    │   │   │   ├── PaymentResponse.java                       ← 8e
    │   │   │   └── RefundResponse.java                        ← 8e
    │   │   ├── mapper/
    │   │   │   ├── OrderMapper.java                           ← 8e
    │   │   │   └── PaymentMapper.java                         ← 8e
    │   │   ├── service/
    │   │   │   ├── OrderService.java                          ← 8g
    │   │   │   ├── RefundService.java                         ← 8g
    │   │   │   ├── IdempotencyService.java                    ← 8h
    │   │   │   ├── EventPublisher.java                        ← 8h
    │   │   │   └── PaymentService.java                        ← 8i
    │   │   ├── kafka/
    │   │   │   └── KafkaEventPublisher.java                   ← 8h
    │   │   ├── sqs/
    │   │   │   └── SqsEventPublisher.java                     ← 8h
    │   │   ├── feign/
    │   │   │   ├── RoutingServiceClient.java                  ← 8j
    │   │   │   └── MerchantServiceClient.java                 ← 8j
    │   │   ├── controller/
    │   │   │   ├── OrderController.java                       ← 8j
    │   │   │   ├── PaymentController.java                     ← 8j
    │   │   │   └── RefundController.java                      ← 8j
    │   │   └── exception/
    │   │       └── PaymentExceptionHandler.java               ← 8j
    │   └── resources/
    │       ├── application.yml                                ← 8a
    │       └── db/migration/
    │           ├── V1__create_orders_table.sql                 ← 8c
    │           ├── V2__create_payments_table.sql               ← 8c
    │           ├── V3__create_payment_methods_table.sql        ← 8c
    │           └── V4__create_refunds_table.sql                ← 8c
    └── test/java/com/payflow/payment/
        ├── controller/
        │   └── PaymentControllerTest.java                     ← 8j
        └── service/
            ├── OrderServiceTest.java                          ← 8g
            ├── PaymentServiceTest.java                        ← 8i
            └── IdempotencyServiceTest.java                    ← 8h
```

---

## 🔐 Key Security Concepts

### Idempotency — Preventing Double Charges

```
PROBLEM:
  Customer clicks "Pay" → network timeout → clicks "Pay" again
  WITHOUT idempotency: TWO charges on the customer's card!

SOLUTION:
  Client sends: Idempotency-Key: unique-uuid-123
  First request:  Process payment → cache result in Redis (24hr TTL)
  Second request: Same key? → Return cached result (NO second charge)

HOW (Redis SET NX EX):
  SET payment:idempotency:unique-uuid-123 "PROCESSING" NX EX 86400
  NX = Only set if Not eXists (atomic lock)
  EX = Expire after 86400 seconds (24 hours)
```

### Two-Phase Payment (Authorize → Capture)

```
WHY TWO STEPS?
  Hotel: Authorize ₹10,000 at check-in (money reserved)
         Capture ₹8,500 at checkout (actual amount charged)
         Void the remaining ₹1,500 (unreserved)

  The authorize step RESERVES money but doesn't MOVE it.
  The capture step MOVES the money.
  This allows the final amount to differ from the authorized amount.
```

---

## ✅ Prerequisites

1. ✅ **common-lib** built: `mvn install -pl common-lib -am -DskipTests`
2. ✅ **PostgreSQL** with `payflow_payment` database:
   ```sql
   CREATE DATABASE payflow_payment;
   GRANT ALL PRIVILEGES ON DATABASE payflow_payment TO payflow;
   \c payflow_payment
   GRANT ALL ON SCHEMA public TO payflow;
   ```
3. ✅ **Redis** running on port 6379:
   ```bash
   # Docker:
   docker-compose up -d redis
   # Or: docker run -d --name redis -p 6379:6379 redis:7-alpine
   ```
4. ✅ **Kafka** running on port 9092 (optional for local — events just fail silently):
   ```bash
   docker-compose up -d zookeeper kafka
   ```

---

## ✅ Final Verification Checklist (After All Parts)

- [ ] Service starts on port 8083
- [ ] Flyway runs 4 migrations
- [ ] `POST /v1/orders` → 201 Created with `order_` prefixed ID
- [ ] `GET /v1/orders/{id}` → 200 with order details
- [ ] Orders expire after 30 minutes (`POST /v1/orders/expire`)
- [ ] `POST /v1/payments/authorize` → 201 with payment + authorization code
- [ ] Duplicate `Idempotency-Key` returns cached response (not double charge)
- [ ] `POST /v1/payments/capture` → 200, order becomes PAID
- [ ] Partial capture (amount < authorized) works
- [ ] `POST /v1/payments/{id}/void` → 200, payment becomes VOIDED
- [ ] `POST /v1/refunds` → 201 with refund details
- [ ] Partial refund works (amount < captured)
- [ ] Over-refund prevented (error when total refunds > payment amount)
- [ ] Kafka events published for authorize/capture/refund
- [ ] All tests pass: `mvn test`
- [ ] Swagger UI: http://localhost:8083/swagger-ui.html

---

## 📚 Navigation

| Document | Title |
|---|---|
| **This document** | **Payment Service Overview & Roadmap** |
| [Part 8a](./phase4-part08a-payment-project-setup.md) | Project Setup (pom.xml, yml, main, security) |
| [Part 8b](./phase4-part08b-payment-entities.md) | Entities (Order, Payment, PaymentMethod, Refund) |
| [Part 8c](./phase4-part08c-payment-migrations.md) | Flyway Migrations (V1-V4 SQL) |
| [Part 8d](./phase4-part08d-payment-repositories.md) | Repositories (4 data access interfaces) |
| [Part 8e](./phase4-part08e-payment-dtos-mappers.md) | DTOs + Mappers (7 DTOs, 2 MapStruct) |
| [Part 8f](./phase4-part08f-payment-configs.md) | Config Classes (Redis, Kafka, Feign) |
| [Part 8g](./phase4-part08g-payment-order-refund-services.md) | OrderService + RefundService + Tests |
| [Part 8h](./phase4-part08h-payment-idempotency-events.md) | IdempotencyService + EventPublisher + Tests |
| [Part 8i](./phase4-part08i-payment-engine.md) | PaymentService Core Engine + Tests |
| [Part 8j](./phase4-part08j-payment-controllers-docker.md) | Controllers + ExceptionHandler + Feign + Docker + curl |
| [Part 8k](./phase4-part08k-payment-connections-flows.md) | How Everything Connects |

---

*Start with [Part 8a — Project Setup](./phase4-part08a-payment-project-setup.md) →*
