# Phase 2: High-Level Design

## Overview

This document details the service decomposition, communication patterns, and key sequence diagrams for PayFlow Payment Gateway. Each flow includes failure modes and recovery strategies.

## Service Decomposition

| Service | Bounded Context | Key Responsibilities | Data Ownership |
|---------|----------------|---------------------|----------------|
| API Gateway | Edge/Routing | Request routing, auth validation, rate limiting | None (stateless) |
| Identity Service | Authentication | User registration, login, JWT management | Users, tokens |
| Merchant Service | Merchant Management | Onboarding, API keys, webhook config, fees | Merchants, keys |
| Payment Service | Payment Processing | Order lifecycle, state machine, idempotency | Orders, transactions |
| Routing Service | Bank Communication | Fraud checks, ISO 8583, acquirer routing | Routing rules |
| Bank Simulator | External System Mock | Simulates bank approve/decline responses | None |
| Settlement Service | Financial Reconciliation | Daily batches, fee calculation, payouts | Batches, payouts |
| Webhook Service | Event Delivery | Reliable webhook delivery with retries | Delivery logs |
| Notification Service | Communication | Email/SMS to end users | Templates |

## Communication Patterns

| From | To | Pattern | Protocol | Why |
|------|----|---------|----------|-----|
| Client | API Gateway | Synchronous | HTTPS/REST | User-facing, needs immediate response |
| Gateway | Any Service | Synchronous | HTTP/REST | Request-response needed for UX |
| Payment | Routing Service | Synchronous | HTTP/REST | Payment auth needs immediate result |
| Routing | Bank Simulator | Synchronous | TCP (ISO 8583) | Protocol requirement, low latency |
| Payment | Kafka | Asynchronous | Kafka Producer | Fire-and-forget event publishing |
| Kafka | Settlement | Asynchronous | Kafka Consumer | Batch processing, no urgency |
| Kafka | Webhook Service | Asynchronous | Kafka Consumer | Reliable delivery with retry |
| Kafka | Notification | Asynchronous | Kafka Consumer | Email/SMS can be delayed |
| Payment | Merchant Service | Synchronous | Feign Client | Validate merchant during payment |

## Full Architecture Diagram

```
                            ┌─────────────────────┐
                            │   LOAD BALANCER     │
                            │   (AWS ALB)         │
                            └─────────┬───────────┘
                                      │
                            ┌─────────▼───────────┐
                            │    API GATEWAY       │
                            │  ┌───────────────┐  │
                            │  │ JWT Filter    │  │
                            │  │ API Key Filter│  │
                            │  │ Rate Limiter  │  │
                            │  │ CORS Config   │  │
                            │  └───────────────┘  │
                            └─────────┬───────────┘
                                      │
              ┌───────────────────────┼───────────────────────┐
              │                       │                       │
    ┌─────────▼─────────┐  ┌─────────▼─────────┐  ┌─────────▼─────────┐
    │ IDENTITY SERVICE  │  │ MERCHANT SERVICE  │  │ PAYMENT SERVICE   │
    │                   │  │                   │  │                   │
    │ • Register        │  │ • Onboarding      │  │ • Create Order    │
    │ • Login           │  │ • API Keys        │  │ • Process Payment │
    │ • Token Refresh   │  │ • Webhook Config  │  │ • Capture         │
    │ • Validate JWT    │  │ • Fee Setup       │  │ • Refund          │
    └────────┬──────────┘  └────────┬──────────┘  └────────┬──────────┘
             │                      │                       │
    ┌────────▼──────────┐  ┌────────▼──────────┐  ┌────────▼──────────┐
    │  PostgreSQL       │  │  PostgreSQL       │  │  PostgreSQL       │
    │  payflow_identity │  │  payflow_merchant │  │  payflow_payment  │
    └───────────────────┘  └───────────────────┘  └───────────────────┘
                                                            │
                                                  ┌─────────▼─────────┐
                                                  │  ROUTING SERVICE  │
                                                  │                   │
                                                  │ • Fraud Detection │
                                                  │ • ISO 8583 Build  │
                                                  │ • Smart Routing   │
                                                  └─────────┬─────────┘
                                                            │ TCP/ISO 8583
                                                  ┌─────────▼─────────┐
                                                  │  BANK SIMULATOR   │
                                                  │  (Netty Server)   │
                                                  └───────────────────┘

    ══════════════════════ APACHE KAFKA ══════════════════════════════
    │ payment.created │ payment.captured │ refund.completed │ ...    │
    ══════════════════════════════════════════════════════════════════
              │                    │                    │
    ┌─────────▼─────────┐ ┌──────▼──────────┐ ┌──────▼──────────┐
    │ SETTLEMENT SERVICE│ │ WEBHOOK SERVICE │ │NOTIFICATION SVC │
    │                   │ │                 │ │                 │
    │ • Daily Batch     │ │ • HTTP POST     │ │ • AWS SES Email │
    │ • Fee Calc        │ │ • HMAC Sign     │ │ • AWS SNS SMS   │
    │ • Payout          │ │ • Retry Logic   │ │ • Templates     │
    └────────┬──────────┘ └────────┬────────┘ └─────────────────┘
             │                     │
    ┌────────▼──────────┐ ┌────────▼────────┐
    │  PostgreSQL       │ │   DynamoDB      │
    │  payflow_settle   │ │   webhook_logs  │
    └───────────────────┘ └─────────────────┘

                    ┌───────────────────────┐
                    │       REDIS           │
                    │  • Idempotency Keys   │
                    │  • Rate Limit Counters│
                    │  • Cache (API Keys)   │
                    │  • Session Data       │
                    └───────────────────────┘
```

## Component Architecture: Payment Service (Example)

```
┌──────────────────────────────────────────────────────────────┐
│                     PAYMENT SERVICE                            │
├──────────────────────────────────────────────────────────────┤
│  Controller Layer                                             │
│  ├── OrderController (REST endpoints)                        │
│  ├── PaymentController (process payment)                     │
│  └── RefundController (initiate refund)                      │
├──────────────────────────────────────────────────────────────┤
│  Service Layer                                                │
│  ├── OrderService (create, get, list orders)                 │
│  ├── PaymentService (state machine, orchestration)           │
│  ├── RefundService (refund logic)                            │
│  └── IdempotencyService (Redis-backed duplicate check)       │
├──────────────────────────────────────────────────────────────┤
│  Integration Layer                                            │
│  ├── RoutingServiceClient (Feign → Routing Service)          │
│  ├── MerchantServiceClient (Feign → Merchant Service)        │
│  └── KafkaEventPublisher (publish payment events)            │
├──────────────────────────────────────────────────────────────┤
│  Repository Layer                                             │
│  ├── OrderRepository (JPA)                                   │
│  ├── TransactionRepository (JPA)                             │
│  └── RefundRepository (JPA)                                  │
├──────────────────────────────────────────────────────────────┤
│  Domain Layer                                                 │
│  ├── Order (entity + state machine)                          │
│  ├── Transaction (entity)                                    │
│  └── Refund (entity)                                         │
└──────────────────────────────────────────────────────────────┘
```

## Sequence Diagrams

### 1. Payment Authorization Flow

```
Customer        Checkout     Gateway    Payment Svc   Routing Svc   Bank
   │               │            │           │             │           │
   │─ Enter Card ─▶│            │           │             │           │
   │               │─ POST /pay▶│           │             │           │
   │               │            │─validate─▶│             │           │
   │               │            │           │─check key──▶│           │
   │               │            │           │◀─valid──────│           │
   │               │            │           │             │           │
   │               │            │           │─ authorize ▶│           │
   │               │            │           │             │─ISO 8583─▶│
   │               │            │           │             │◀─response─│
   │               │            │           │◀─ result ───│           │
   │               │            │           │             │           │
   │               │            │           │─ Kafka: payment.authorized ──▶
   │               │            │◀─ 200 ────│             │           │
   │               │◀─ result ──│           │             │           │
   │◀─ Show result─│            │           │             │           │
```

**Failure Modes:**
- Bank timeout (> 30s) → Return TIMEOUT, retry once
- Bank declined → Return DECLINED with reason code
- Routing Service down → Circuit breaker opens, return SERVICE_UNAVAILABLE
- Kafka publish fails → Log to DB, retry via scheduler

### 2. Payment Capture Flow

```
Merchant Backend    Gateway    Payment Svc    DB
      │                │           │          │
      │─POST /capture─▶│           │          │
      │                │─validate─▶│          │
      │                │           │─get order▶│
      │                │           │◀─AUTHORIZED│
      │                │           │           │
      │                │           │─update───▶│
      │                │           │  CAPTURED  │
      │                │           │           │
      │                │           │─ Kafka: payment.captured ──▶
      │                │◀─ 200 ────│          │
      │◀─── success ───│           │          │
```

**Failure Modes:**
- Order not in AUTHORIZED state → Return 409 CONFLICT
- Idempotent retry (same capture request) → Return cached response
- DB write fails → Retry with exponential backoff

### 3. Refund Flow

```
Merchant      Gateway    Payment Svc    Routing Svc    Bank
   │             │           │              │            │
   │─POST /refund▶           │              │            │
   │             │─validate─▶│              │            │
   │             │           │─check order──│            │
   │             │           │  (CAPTURED?) │            │
   │             │           │              │            │
   │             │           │─refund req──▶│            │
   │             │           │              │─ISO 8583──▶│
   │             │           │              │◀─approved──│
   │             │           │◀─success─────│            │
   │             │           │              │            │
   │             │           │─update: REFUNDED          │
   │             │           │─Kafka: refund.completed──▶│
   │             │◀─200──────│              │            │
   │◀─success────│           │              │            │
```

**Failure Modes:**
- Refund amount > captured amount → Return 400 BAD_REQUEST
- Partial refund exceeds remaining → Return 400 with remaining amount
- Bank declines refund → Mark as REFUND_FAILED, notify merchant

### 4. Merchant Onboarding Flow

```
Merchant     Dashboard    Gateway    Identity Svc    Merchant Svc    DB
   │            │            │           │               │           │
   │─register──▶            │           │               │           │
   │            │─POST /register▶        │               │           │
   │            │            │─create───▶│               │           │
   │            │            │           │─hash pwd─────▶│           │
   │            │            │           │─save user────▶│           │
   │            │            │◀─JWT──────│               │           │
   │            │◀─token─────│           │               │           │
   │            │            │           │               │           │
   │─setup biz─▶            │           │               │           │
   │            │─POST /merchants▶       │               │           │
   │            │            │──────────────────────────▶│           │
   │            │            │           │               │─validate──│
   │            │            │           │               │─save──────▶
   │            │            │           │               │─gen API key▶
   │            │            │◀─────────────────────────│           │
   │            │◀─merchant created──────│               │           │
   │◀─show keys─│            │           │               │           │
```

### 5. Webhook Delivery Flow

```
Payment Svc     Kafka     Webhook Svc    DynamoDB    Merchant Server
     │            │           │             │              │
     │─publish───▶│           │             │              │
     │            │─consume──▶│             │              │
     │            │           │─build payload│              │
     │            │           │─sign HMAC────│              │
     │            │           │             │              │
     │            │           │─POST (attempt 1)──────────▶│
     │            │           │             │         ┌────│
     │            │           │             │         │timeout
     │            │           │             │         └────│
     │            │           │─log attempt─▶             │
     │            │           │             │              │
     │            │           │─wait 30s────│              │
     │            │           │─POST (attempt 2)──────────▶│
     │            │           │◀──────────── 200 ─────────│
     │            │           │─log success─▶             │
     │            │           │             │              │
```

**Retry Schedule:** 30s → 2min → 10min → 1hr → 4hr → 24hr (max 6 attempts)

### 6. Settlement Batch Flow

```
Scheduler     Settlement Svc    Payment DB    Merchant Svc    Bank
    │              │                │              │            │
    │─trigger──────▶                │              │            │
    │  (daily 2AM) │                │              │            │
    │              │─query captured─▶              │            │
    │              │  (yesterday)    │              │            │
    │              │◀─transactions───│              │            │
    │              │                 │              │            │
    │              │─get fee config─────────────────▶            │
    │              │◀─MDR rates─────────────────────│            │
    │              │                 │              │            │
    │              │─calculate:      │              │            │
    │              │  gross_amount   │              │            │
    │              │  - MDR (2%)     │              │            │
    │              │  - GST (18% on MDR)           │            │
    │              │  = net_payout   │              │            │
    │              │                 │              │            │
    │              │─create batch────▶              │            │
    │              │─create records──▶              │            │
    │              │                 │              │            │
    │              │─initiate payout────────────────────────────▶│
    │              │◀─UTR reference─────────────────────────────│
    │              │─update status───▶              │            │
    │              │  (SETTLED)      │              │            │
    │              │─Kafka: settlement.completed───▶│            │
```

### 7. User Registration Flow

```
User        Dashboard    Gateway    Identity Svc    DB     Redis
  │            │            │           │           │        │
  │─register──▶            │           │           │        │
  │            │─POST──────▶│           │           │        │
  │            │            │─route────▶│           │        │
  │            │            │           │─validate──│        │
  │            │            │           │  (email   │        │
  │            │            │           │   unique?)│        │
  │            │            │           │◀─yes──────│        │
  │            │            │           │           │        │
  │            │            │           │─BCrypt────│        │
  │            │            │           │  hash pwd │        │
  │            │            │           │─save user▶│        │
  │            │            │           │           │        │
  │            │            │           │─gen JWT───│        │
  │            │            │           │─cache─────────────▶│
  │            │            │◀─tokens───│           │        │
  │            │◀─200+tokens│           │           │        │
  │◀─logged in─│            │           │           │        │
```

## Error Handling Strategy

| Error Type | HTTP Status | Recovery |
|-----------|-------------|----------|
| Validation Error | 400 | Return field-level errors |
| Authentication Failed | 401 | Redirect to login |
| Insufficient Permissions | 403 | Show access denied |
| Resource Not Found | 404 | Return helpful message |
| Duplicate Request | 409 | Return cached response (idempotency) |
| Rate Limit Exceeded | 429 | Return retry-after header |
| Service Unavailable | 503 | Circuit breaker, show retry |
| Internal Error | 500 | Log, alert, generic message to user |

## Cross-Cutting Concerns

| Concern | Solution |
|---------|----------|
| Distributed Tracing | Correlation ID passed in headers |
| Logging | Structured JSON logs (SLF4J + Logback) |
| Health Checks | Spring Actuator `/health` endpoint |
| Configuration | Externalized via environment variables |
| Secret Management | AWS Secrets Manager (prod), env vars (dev) |
| API Versioning | URL-based (`/api/v1/`) |
