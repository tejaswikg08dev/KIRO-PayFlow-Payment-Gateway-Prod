# 🏗️ Phase 2: High-Level Design (HLD)

> **"Architecture is the art of drawing lines that separate the things that change from the things that don't."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 2 — High-Level Design |
| **Previous** | [phase1-system-design.md](./phase1-system-design.md) |
| **Next** | [phase3-low-level-design.md](./phase3-low-level-design.md) |
| **Author** | Tejaswi |
| **Created** | 2024 |
| **Status** | Living Document |
| **Audience** | System Design Learners, Backend Engineers |

---

## 📖 Table of Contents

1. [System Architecture Overview](#system-architecture-overview)
2. [Service Communication Matrix](#service-communication-matrix)
3. [Sequence Diagrams](#sequence-diagrams)
4. [Spring Cloud Filter Chain](#spring-cloud-filter-chain)
5. [Docker Compose Architecture](#docker-compose-architecture)
6. [Data Flow Patterns](#data-flow-patterns)
7. [Failure Modes and Recovery](#failure-modes-and-recovery)
8. [What You Learned](#what-you-learned)
9. [Document Index](#document-index)

---

## 🏛️ System Architecture Overview

### Complete Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              PAYFLOW PAYMENT GATEWAY                                  │
│                          Complete System Architecture                                 │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                       │
│  ┌─────────────────────────────────────────────────────────────────────────────┐     │
│  │                        CLIENT TIER (Frontend)                                │     │
│  │                                                                               │     │
│  │   ┌──────────────┐    ┌──────────────┐    ┌──────────────────────────┐      │     │
│  │   │  Merchant    │    │   Hosted     │    │  Server-to-Server API    │      │     │
│  │   │  Dashboard   │    │  Checkout    │    │  (Merchant Backend)      │      │     │
│  │   │  (React+TS)  │    │  (React+TS)  │    │  (REST + API Key)       │      │     │
│  │   └──────┬───────┘    └──────┬───────┘    └────────────┬─────────────┘      │     │
│  │          │                    │                         │                     │     │
│  └──────────┼────────────────────┼─────────────────────────┼─────────────────────┘     │
│             │ HTTPS/JWT          │ HTTPS/Session            │ HTTPS/API-Key+HMAC        │
│             ▼                    ▼                         ▼                            │
│  ┌─────────────────────────────────────────────────────────────────────────────┐     │
│  │                        EDGE TIER (API Gateway)                               │     │
│  │                                                                               │     │
│  │   ┌─────────────────────────────────────────────────────────────────────┐   │     │
│  │   │              Spring Cloud Gateway (WebFlux)                          │   │     │
│  │   │                                                                       │   │     │
│  │   │  ┌─────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ │   │     │
│  │   │  │ Request │ │   JWT    │ │ API Key  │ │  Rate    │ │  Route   │ │   │     │
│  │   │  │ Logging │→│ Validate │→│ Validate │→│ Limiter  │→│ Forward  │ │   │     │
│  │   │  └─────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘ │   │     │
│  │   └─────────────────────────────────────────────────────────────────────┘   │     │
│  │                                                                               │     │
│  └───────────────────────────────────┬───────────────────────────────────────────┘     │
│                                      │                                                  │
│  ┌───────────────────────────────────┼───────────────────────────────────────────┐     │
│  │                        SERVICE TIER (Microservices)                            │     │
│  │                                   │                                            │     │
│  │    ┌──────────────┐ ┌─────────────▼──┐ ┌──────────────┐ ┌──────────────┐    │     │
│  │    │   Identity   │ │    Payment     │ │   Merchant   │ │   Routing    │    │     │
│  │    │   Service    │ │    Service     │ │   Service    │ │   Service    │    │     │
│  │    │              │ │                │ │              │ │              │    │     │
│  │    │ • Register   │ │ • Create Order │ │ • Onboard    │ │ • ISO 8583   │    │     │
│  │    │ • Login      │ │ • Authorize    │ │ • API Keys   │ │ • Smart Route│    │     │
│  │    │ • JWT Issue  │ │ • Capture      │ │ • Webhooks   │ │ • Fraud Check│    │     │
│  │    │ • Refresh    │ │ • Refund       │ │ • Fees       │ │ • Circuit Brk│    │     │
│  │    └──────┬───────┘ └───────┬────────┘ └──────┬───────┘ └──────┬───────┘    │     │
│  │           │                  │                  │                │            │     │
│  │    ┌──────┼──────────────────┼──────────────────┼────────────────┼──────┐    │     │
│  │    │      ▼                  ▼                  ▼                ▼      │    │     │
│  │    │               Apache Kafka (Event Bus)                            │    │     │
│  │    │  Topics: payment.events, webhook.events, notification.events      │    │     │
│  │    └──────┬──────────────────┬──────────────────┬────────────────┬──────┘    │     │
│  │           │                  │                  │                │            │     │
│  │    ┌──────▼───────┐ ┌───────▼────────┐ ┌──────▼───────┐ ┌──────▼───────┐    │     │
│  │    │  Settlement  │ │    Webhook     │ │ Notification │ │    Bank      │    │     │
│  │    │  Service     │ │    Service     │ │   Service    │ │  Simulator   │    │     │
│  │    │              │ │                │ │              │ │              │    │     │
│  │    │ • Batch Calc │ │ • HMAC Sign    │ │ • Email      │ │ • TCP Server │    │     │
│  │    │ • Fee Deduct │ │ • HTTP Post    │ │ • SMS        │ │ • ISO 8583   │    │     │
│  │    │ • Reports    │ │ • Retry Queue  │ │ • Templates  │ │ • Simulator  │    │     │
│  │    └──────────────┘ └────────────────┘ └──────────────┘ └──────────────┘    │     │
│  │                                                                               │     │
│  └───────────────────────────────────────────────────────────────────────────────┘     │
│                                                                                       │
│  ┌───────────────────────────────────────────────────────────────────────────────┐     │
│  │                        DATA TIER (Persistence)                                │     │
│  │                                                                               │     │
│  │   ┌─────────────┐ ┌──────────────┐ ┌────────────┐ ┌─────────────────────┐  │     │
│  │   │ PostgreSQL  │ │   Redis      │ │  DynamoDB  │ │   Kafka (Log)       │  │     │
│  │   │             │ │              │ │            │ │                     │  │     │
│  │   │ • Users     │ │ • JWT Cache  │ │ • Webhooks │ │ • Event Sourcing   │  │     │
│  │   │ • Merchants │ │ • Rate Limit │ │ • Delivery │ │ • Audit Trail      │  │     │
│  │   │ • Payments  │ │ • Sessions   │ │ • Logs     │ │ • Replay           │  │     │
│  │   │ • Orders    │ │ • Idempotency│ │            │ │                     │  │     │
│  │   └─────────────┘ └──────────────┘ └────────────┘ └─────────────────────┘  │     │
│  │                                                                               │     │
│  └───────────────────────────────────────────────────────────────────────────────┘     │
│                                                                                       │
│  ┌───────────────────────────────────────────────────────────────────────────────┐     │
│  │                    INFRASTRUCTURE TIER (Spring Cloud)                          │     │
│  │                                                                               │     │
│  │   ┌─────────────┐ ┌──────────────┐ ┌────────────────────────────────────┐   │     │
│  │   │  Eureka     │ │ Config       │ │  Observability                     │   │     │
│  │   │  Service    │ │ Server       │ │                                    │   │     │
│  │   │  Registry   │ │              │ │  • Actuator → Prometheus → Grafana │   │     │
│  │   │             │ │ • Git-backed │ │  • Micrometer Tracing → Zipkin     │   │     │
│  │   │ • Discovery │ │ • Per-profile│ │  • Structured Logging → ELK       │   │     │
│  │   │ • Health    │ │ • Encryption │ │                                    │   │     │
│  │   └─────────────┘ └──────────────┘ └────────────────────────────────────┘   │     │
│  │                                                                               │     │
│  └───────────────────────────────────────────────────────────────────────────────┘     │
│                                                                                       │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Service Responsibilities Table

| Service | Port | Database | Key Technology | Responsibility |
|---------|------|----------|----------------|----------------|
| Service Registry | 8761 | None | Spring Cloud Netflix Eureka | Service discovery, health monitoring |
| Config Server | 8888 | Git repo | Spring Cloud Config | Centralized configuration management |
| API Gateway | 8080 | Redis | Spring Cloud Gateway (WebFlux) | Routing, auth, rate limiting, CORS |
| Identity Service | 8081 | PostgreSQL (payflow_identity) | Spring Security, JJWT | Authentication, JWT, user management |
| Merchant Service | 8082 | PostgreSQL (payflow_merchant) | Spring Data JPA | Merchant CRUD, API keys, webhooks |
| Payment Service | 8083 | PostgreSQL (payflow_payment) | Kafka, Feign | Order/payment lifecycle, idempotency |
| Routing Service | 8084 | PostgreSQL (payflow_routing) | Netty, ISO 8583, Resilience4j | Bank communication, fraud, routing |
| Settlement Service | 8085 | PostgreSQL (payflow_settlement) | Spring Batch | Fee calculation, batch settlement |
| Webhook Service | 8086 | DynamoDB | Kafka, HMAC-SHA256 | Event delivery with retry |
| Notification Service | 8087 | PostgreSQL (payflow_notification) | Kafka, Thymeleaf | Email/SMS notifications |
| Bank Simulator | 9090 | In-memory | Netty TCP Server | Simulate acquiring bank responses |

---

## 🔄 Service Communication Matrix

### Synchronous Communication (REST/Feign)

| From → To | Protocol | Purpose | Circuit Breaker |
|-----------|----------|---------|-----------------|
| Gateway → Identity | HTTP/REST | JWT validation, user lookup | Yes |
| Gateway → Merchant | HTTP/REST | API key validation | Yes |
| Gateway → Payment | HTTP/REST | Forward payment requests | Yes |
| Payment → Routing | HTTP/Feign | Route payment to bank | Yes |
| Payment → Merchant | HTTP/Feign | Validate merchant exists | Yes |
| Routing → Bank Sim | TCP/ISO8583 | Send financial messages | Yes |

### Asynchronous Communication (Kafka)

| Producer | Topic | Consumers | Event Type |
|----------|-------|-----------|------------|
| Payment Service | `payment.events` | Webhook, Notification, Settlement | PaymentAuthorized, PaymentCaptured, PaymentFailed, RefundProcessed |
| Merchant Service | `merchant.events` | Notification | MerchantOnboarded, ApiKeyRotated |
| Identity Service | `identity.events` | Notification | UserRegistered |
| Settlement Service | `settlement.events` | Notification, Webhook | SettlementCompleted |

---

## 📐 Sequence Diagrams

### 1. Payment Authorization Flow

```
┌────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌──────────┐    ┌──────┐
│Merchant│    │API Gate │    │Payment  │    │Routing  │    │Bank Sim  │    │Kafka │
│Backend │    │  way    │    │Service  │    │Service  │    │(Acquirer)│    │      │
└───┬────┘    └────┬────┘    └────┬────┘    └────┬────┘    └────┬─────┘    └──┬───┘
    │              │              │              │               │             │
    │ POST /api/v1/payments/authorize           │               │             │
    │─────────────►│              │              │               │             │
    │              │              │              │               │             │
    │              │ Validate API Key            │               │             │
    │              │ (X-API-Key header)          │               │             │
    │              │──────┐       │              │               │             │
    │              │      │       │              │               │             │
    │              │◄─────┘       │              │               │             │
    │              │              │              │               │             │
    │              │ Validate JWT │              │               │             │
    │              │──────┐       │              │               │             │
    │              │      │       │              │               │             │
    │              │◄─────┘       │              │               │             │
    │              │              │              │               │             │
    │              │ Rate Limit Check            │               │             │
    │              │──────┐       │              │               │             │
    │              │      │       │              │               │             │
    │              │◄─────┘       │              │               │             │
    │              │              │              │               │             │
    │              │ Forward Request             │               │             │
    │              │─────────────►│              │               │             │
    │              │              │              │               │             │
    │              │              │ Check Idempotency Key        │             │
    │              │              │──────┐       │               │             │
    │              │              │      │ Redis │               │             │
    │              │              │◄─────┘       │               │             │
    │              │              │              │               │             │
    │              │              │ Validate Merchant (Feign)    │             │
    │              │              │─────────────►│               │             │
    │              │              │◄─────────────│ (via Merchant)│             │
    │              │              │              │               │             │
    │              │              │ Save Order (CREATED)         │             │
    │              │              │──────┐       │               │             │
    │              │              │      │ DB    │               │             │
    │              │              │◄─────┘       │               │             │
    │              │              │              │               │             │
    │              │              │ Route Payment│               │             │
    │              │              │─────────────►│               │             │
    │              │              │              │               │             │
    │              │              │              │ Fraud Check   │             │
    │              │              │              │──────┐        │             │
    │              │              │              │      │        │             │
    │              │              │              │◄─────┘        │             │
    │              │              │              │               │             │
    │              │              │              │ Smart Route   │             │
    │              │              │              │──────┐        │             │
    │              │              │              │      │        │             │
    │              │              │              │◄─────┘        │             │
    │              │              │              │               │             │
    │              │              │              │ ISO 8583 Auth │             │
    │              │              │              │ (MTI 0100)    │             │
    │              │              │              │──────────────►│             │
    │              │              │              │               │             │
    │              │              │              │ ISO 8583 Resp │             │
    │              │              │              │ (MTI 0110)    │             │
    │              │              │              │◄──────────────│             │
    │              │              │              │               │             │
    │              │              │ Auth Response│               │             │
    │              │              │◄─────────────│               │             │
    │              │              │              │               │             │
    │              │              │ Update Order (AUTHORIZED)    │             │
    │              │              │──────┐       │               │             │
    │              │              │      │ DB    │               │             │
    │              │              │◄─────┘       │               │             │
    │              │              │              │               │             │
    │              │              │ Publish Event│               │             │
    │              │              │──────────────────────────────────────────►│
    │              │              │              │               │  payment.   │
    │              │              │              │               │  authorized │
    │              │              │              │               │             │
    │              │ 200 OK       │              │               │             │
    │◄─────────────│◄─────────────│              │               │             │
    │              │              │              │               │             │
    │ { orderId, status: AUTHORIZED, authCode }  │               │             │
    │              │              │              │               │             │
```

**Failure Modes:**
| Failure Point | Behavior | Recovery |
|---------------|----------|----------|
| API Key invalid | 401 Unauthorized | Merchant regenerates key |
| Rate limit exceeded | 429 Too Many Requests | Retry after cooldown |
| Idempotency key exists | Return cached response | No retry needed |
| Merchant not found | 404 Not Found | Check merchant ID |
| Fraud check fails | 402 Payment Declined | Flag for review |
| Bank timeout | 504 Gateway Timeout | Circuit breaker opens, retry on backup |
| Bank decline | 402 Payment Declined | Return decline reason code |
| Kafka publish fails | Log + retry async | Dead letter queue |

---

### 2. Payment Capture Flow

```
┌────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌──────────┐    ┌──────┐
│Merchant│    │API Gate │    │Payment  │    │Routing  │    │Bank Sim  │    │Kafka │
│Backend │    │  way    │    │Service  │    │Service  │    │(Acquirer)│    │      │
└───┬────┘    └────┬────┘    └────┬────┘    └────┬────┘    └────┬─────┘    └──┬───┘
    │              │              │              │               │             │
    │ POST /api/v1/payments/{id}/capture        │               │             │
    │ { amount: 800.00 }         │              │               │             │
    │─────────────►│              │              │               │             │
    │              │              │              │               │             │
    │              │ Auth + Forward              │               │             │
    │              │─────────────►│              │               │             │
    │              │              │              │               │             │
    │              │              │ Validate:    │               │             │
    │              │              │ - Order exists│              │             │
    │              │              │ - Status = AUTHORIZED        │             │
    │              │              │ - Amount ≤ Auth amount       │             │
    │              │              │ - Not expired (7 days)       │             │
    │              │              │──────┐       │               │             │
    │              │              │      │       │               │             │
    │              │              │◄─────┘       │               │             │
    │              │              │              │               │             │
    │              │              │ ISO 8583 Capture (MTI 0220)  │             │
    │              │              │─────────────►│──────────────►│             │
    │              │              │              │               │             │
    │              │              │              │◄──────────────│             │
    │              │              │◄─────────────│ (MTI 0230)    │             │
    │              │              │              │               │             │
    │              │              │ Update: CAPTURED             │             │
    │              │              │──────┐       │               │             │
    │              │              │      │       │               │             │
    │              │              │◄─────┘       │               │             │
    │              │              │              │               │             │
    │              │              │ Publish: payment.captured    │             │
    │              │              │──────────────────────────────────────────►│
    │              │              │              │               │             │
    │ 200 OK { status: CAPTURED }│              │               │             │
    │◄─────────────│◄─────────────│              │               │             │
```

**Failure Modes:**
| Failure Point | Behavior | Recovery |
|---------------|----------|----------|
| Order not found | 404 Not Found | Verify order ID |
| Already captured | 409 Conflict | Idempotent — return original |
| Auth expired (>7 days) | 422 Unprocessable | Re-authorize required |
| Amount > authorized | 400 Bad Request | Reduce capture amount |
| Bank rejects capture | 502 Bad Gateway | Retry or escalate |

---

### 3. Refund Flow

```
┌────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌──────────┐    ┌──────┐
│Merchant│    │API Gate │    │Payment  │    │Routing  │    │Bank Sim  │    │Kafka │
│Backend │    │  way    │    │Service  │    │Service  │    │(Acquirer)│    │      │
└───┬────┘    └────┬────┘    └────┬────┘    └────┬────┘    └────┬─────┘    └──┬───┘
    │              │              │              │               │             │
    │ POST /api/v1/payments/{id}/refund         │               │             │
    │ { amount: 200.00, reason: "Customer request" }            │             │
    │─────────────►│              │              │               │             │
    │              │              │              │               │             │
    │              │ Auth + Forward              │               │             │
    │              │─────────────►│              │               │             │
    │              │              │              │               │             │
    │              │              │ Validate:    │               │             │
    │              │              │ - Status = CAPTURED/SETTLED  │             │
    │              │              │ - Refund amount ≤ remaining  │             │
    │              │              │ - Sum of refunds ≤ captured  │             │
    │              │              │──────┐       │               │             │
    │              │              │      │       │               │             │
    │              │              │◄─────┘       │               │             │
    │              │              │              │               │             │
    │              │              │ Create Refund record         │             │
    │              │              │──────┐       │               │             │
    │              │              │      │       │               │             │
    │              │              │◄─────┘       │               │             │
    │              │              │              │               │             │
    │              │              │ ISO 8583 Reversal (MTI 0420) │             │
    │              │              │─────────────►│──────────────►│             │
    │              │              │              │◄──────────────│ (MTI 0430)  │
    │              │              │◄─────────────│               │             │
    │              │              │              │               │             │
    │              │              │ Update: REFUNDED (partial)   │             │
    │              │              │──────┐       │               │             │
    │              │              │      │       │               │             │
    │              │              │◄─────┘       │               │             │
    │              │              │              │               │             │
    │              │              │ Publish: refund.processed    │             │
    │              │              │──────────────────────────────────────────►│
    │              │              │              │               │             │
    │ 200 OK { refundId, amount, status }       │               │             │
    │◄─────────────│◄─────────────│              │               │             │
```

**Failure Modes:**
| Failure Point | Behavior | Recovery |
|---------------|----------|----------|
| Not captured yet | 422 Unprocessable | Wait for capture |
| Exceeds refundable amount | 400 Bad Request | Reduce refund amount |
| Bank rejects reversal | 502 Bad Gateway | Manual reconciliation |
| Partial refund tracking | Sum all refunds | Query refund history |

---

### 4. Merchant Onboarding Flow

```
┌─────────┐    ┌─────────┐    ┌──────────┐    ┌──────────┐    ┌──────┐
│Merchant │    │API Gate │    │ Identity │    │ Merchant │    │Kafka │
│(Person) │    │  way    │    │ Service  │    │ Service  │    │      │
└───┬─────┘    └────┬────┘    └────┬─────┘    └────┬─────┘    └──┬───┘
    │               │              │               │              │
    │ POST /api/v1/auth/register   │               │              │
    │ { email, password, fullName, role: MERCHANT }│              │
    │──────────────►│              │               │              │
    │               │─────────────►│               │              │
    │               │              │               │              │
    │               │              │ Create User   │              │
    │               │              │──────┐        │              │
    │               │              │      │        │              │
    │               │              │◄─────┘        │              │
    │               │              │               │              │
    │ 201 { accessToken, refreshToken, user }      │              │
    │◄──────────────│◄─────────────│               │              │
    │               │              │               │              │
    │ POST /api/v1/merchants       │               │              │
    │ { businessName, pan, gst, bankAccount, ... } │              │
    │──────────────►│              │               │              │
    │               │──────────────────────────────►│              │
    │               │              │               │              │
    │               │              │               │ Create Merchant
    │               │              │               │──────┐       │
    │               │              │               │      │ DB    │
    │               │              │               │◄─────┘       │
    │               │              │               │              │
    │               │              │               │ Generate API Key
    │               │              │               │──────┐       │
    │               │              │               │      │SHA256 │
    │               │              │               │◄─────┘       │
    │               │              │               │              │
    │               │              │               │ Publish Event│
    │               │              │               │─────────────►│
    │               │              │               │  merchant.   │
    │               │              │               │  onboarded   │
    │               │              │               │              │
    │ 201 { merchantId, apiKey (shown ONCE) }      │              │
    │◄──────────────│◄─────────────────────────────│              │
```

**Failure Modes:**
| Failure Point | Behavior | Recovery |
|---------------|----------|----------|
| Duplicate email | 409 Conflict | Use existing account |
| Invalid PAN/GST format | 400 Bad Request | Fix input |
| API key generation fails | 500 Internal Error | Retry registration |
| Kafka event fails | Merchant created, event lost | Retry via outbox pattern |

---

### 5. User Registration Flow

```
┌────────┐    ┌─────────┐    ┌──────────┐    ┌──────────┐    ┌──────┐
│ User   │    │API Gate │    │ Identity │    │PostgreSQL│    │Kafka │
│(Browser│    │  way    │    │ Service  │    │          │    │      │
└───┬────┘    └────┬────┘    └────┬─────┘    └────┬─────┘    └──┬───┘
    │              │              │               │              │
    │ POST /api/v1/auth/register  │               │              │
    │ { email, password, fullName }               │              │
    │─────────────►│              │               │              │
    │              │              │               │              │
    │              │ No auth required (public)    │              │
    │              │─────────────►│               │              │
    │              │              │               │              │
    │              │              │ Check duplicate email        │
    │              │              │──────────────►│              │
    │              │              │◄──────────────│              │
    │              │              │               │              │
    │              │              │ BCrypt hash password         │
    │              │              │──────┐        │              │
    │              │              │      │ cost=12│              │
    │              │              │◄─────┘        │              │
    │              │              │               │              │
    │              │              │ INSERT user   │              │
    │              │              │──────────────►│              │
    │              │              │◄──────────────│              │
    │              │              │               │              │
    │              │              │ Generate JWT  │              │
    │              │              │──────┐        │              │
    │              │              │      │HMAC-256│              │
    │              │              │◄─────┘        │              │
    │              │              │               │              │
    │              │              │ Save Refresh Token           │
    │              │              │──────────────►│              │
    │              │              │◄──────────────│              │
    │              │              │               │              │
    │              │              │ Publish: user.registered     │
    │              │              │─────────────────────────────►│
    │              │              │               │              │
    │ 201 Created  │              │               │              │
    │ { accessToken, refreshToken, expiresIn, user }            │
    │◄─────────────│◄─────────────│               │              │
```

---

### 6. Webhook Delivery Flow

```
┌──────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌────────────┐
│Kafka │    │ Webhook  │    │ DynamoDB │    │ Merchant │    │  Merchant  │
│      │    │ Service  │    │          │    │ Service  │    │  Endpoint  │
└──┬───┘    └────┬─────┘    └────┬─────┘    └────┬─────┘    └─────┬──────┘
   │             │              │               │                 │
   │ payment.    │              │               │                 │
   │ authorized  │              │               │                 │
   │────────────►│              │               │                 │
   │             │              │               │                 │
   │             │ Fetch webhook config         │                 │
   │             │─────────────────────────────►│                 │
   │             │◄─────────────────────────────│                 │
   │             │ { url, secret, events[] }    │                 │
   │             │              │               │                 │
   │             │ Generate HMAC-SHA256 signature│                 │
   │             │──────┐       │               │                 │
   │             │      │       │               │                 │
   │             │◄─────┘       │               │                 │
   │             │              │               │                 │
   │             │ Save delivery attempt        │                 │
   │             │─────────────►│               │                 │
   │             │◄─────────────│               │                 │
   │             │              │               │                 │
   │             │ POST webhook URL             │                 │
   │             │ Headers: X-PayFlow-Signature │                 │
   │             │──────────────────────────────────────────────►│
   │             │              │               │                 │
   │             │ 200 OK       │               │                 │
   │             │◄──────────────────────────────────────────────│
   │             │              │               │                 │
   │             │ Update: DELIVERED            │                 │
   │             │─────────────►│               │                 │
   │             │◄─────────────│               │                 │
```

**Retry Logic (on failure):**
```
Attempt 1: Immediate
Attempt 2: After 5 minutes
Attempt 3: After 30 minutes
Attempt 4: After 2 hours
Attempt 5: After 24 hours
→ After 5 failures: Mark as FAILED, alert merchant
```

**Failure Modes:**
| Failure Point | Behavior | Recovery |
|---------------|----------|----------|
| Merchant endpoint down | Exponential backoff retry | 5 attempts over 24h |
| Timeout (>30s) | Treat as failure, retry | Same retry policy |
| 4xx response | Do NOT retry (client error) | Merchant fixes endpoint |
| 5xx response | Retry with backoff | Auto-heal expected |
| HMAC validation fails | Merchant rejects webhook | Rotate webhook secret |

---

### 7. Settlement Flow

```
┌───────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────┐
│ Scheduler │    │Settlement│    │PostgreSQL│    │ Merchant │    │Kafka │
│ (Cron)    │    │ Service  │    │          │    │ Service  │    │      │
└─────┬─────┘    └────┬─────┘    └────┬─────┘    └────┬─────┘    └──┬───┘
      │               │              │               │              │
      │ Trigger at 23:59 IST        │               │              │
      │──────────────►│              │               │              │
      │               │              │               │              │
      │               │ Fetch CAPTURED payments (today)            │
      │               │──────────────►│               │              │
      │               │◄──────────────│               │              │
      │               │              │               │              │
      │               │ Group by merchant             │              │
      │               │──────┐       │               │              │
      │               │      │       │               │              │
      │               │◄─────┘       │               │              │
      │               │              │               │              │
      │               │ Fetch fee config per merchant│              │
      │               │─────────────────────────────►│              │
      │               │◄─────────────────────────────│              │
      │               │              │               │              │
      │               │ Calculate fees:              │              │
      │               │ - Platform fee (2.0%)        │              │
      │               │ - GST on fee (18%)           │              │
      │               │ - Net = captured - fees      │              │
      │               │──────┐       │               │              │
      │               │      │       │               │              │
      │               │◄─────┘       │               │              │
      │               │              │               │              │
      │               │ Create settlement records    │              │
      │               │──────────────►│               │              │
      │               │◄──────────────│               │              │
      │               │              │               │              │
      │               │ Update payments: SETTLED     │              │
      │               │──────────────►│               │              │
      │               │◄──────────────│               │              │
      │               │              │               │              │
      │               │ Publish: settlement.completed│              │
      │               │─────────────────────────────────────────────►│
      │               │              │               │              │
```

**Settlement Calculation Example:**
```
Merchant: "TechStore India"
Captured Payments Today:
  - ORD-001: ₹1,000.00
  - ORD-002: ₹2,500.00
  - ORD-003: ₹750.00
  Total Captured: ₹4,250.00

Fee Calculation:
  Platform Fee (2.0%): ₹85.00
  GST on Fee (18%):    ₹15.30
  Total Deductions:    ₹100.30

Net Settlement: ₹4,149.70
```

---

### 8. Smart Routing Flow

```
┌─────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│Payment  │    │ Routing  │    │ Fraud    │    │  Smart   │    │ Bank     │
│Service  │    │ Service  │    │ Engine   │    │ Router   │    │ (via TCP)│
└────┬────┘    └────┬─────┘    └────┬─────┘    └────┬─────┘    └────┬─────┘
     │              │              │               │               │
     │ Route Payment│              │               │               │
     │─────────────►│              │               │               │
     │              │              │               │               │
     │              │ Fraud Score  │               │               │
     │              │─────────────►│               │               │
     │              │              │               │               │
     │              │              │ Rules:        │               │
     │              │              │ - Velocity    │               │
     │              │              │ - Amount      │               │
     │              │              │ - Geo         │               │
     │              │              │ - Blocklist   │               │
     │              │              │──────┐        │               │
     │              │              │      │        │               │
     │              │              │◄─────┘        │               │
     │              │              │               │               │
     │              │ Score: 25/100 (PASS)         │               │
     │              │◄─────────────│               │               │
     │              │              │               │               │
     │              │ Select Route │               │               │
     │              │─────────────────────────────►│               │
     │              │              │               │               │
     │              │              │               │ Criteria:     │
     │              │              │               │ 1. Card BIN → Bank
     │              │              │               │ 2. Success Rate│
     │              │              │               │ 3. Cost       │
     │              │              │               │ 4. Load Balance│
     │              │              │               │──────┐        │
     │              │              │               │      │        │
     │              │              │               │◄─────┘        │
     │              │              │               │               │
     │              │ Selected: HDFC Bank (Route A)│               │
     │              │◄─────────────────────────────│               │
     │              │              │               │               │
     │              │ Send ISO 8583 via Netty      │               │
     │              │─────────────────────────────────────────────►│
     │              │              │               │               │
     │              │◄─────────────────────────────────────────────│
     │              │              │               │               │
     │ Response     │              │               │               │
     │◄─────────────│              │               │               │
```

**Routing Decision Matrix:**
| Factor | Weight | Example |
|--------|--------|---------|
| Card BIN Match | 40% | HDFC card → HDFC acquirer (on-us) |
| Historical Success Rate | 30% | Bank A: 98.5%, Bank B: 95.2% |
| Transaction Cost | 20% | Bank A: ₹3/txn, Bank B: ₹5/txn |
| Current Load | 10% | Bank A: 80% capacity, Bank B: 40% |

---

### 9. Fraud Detection Flow

```
┌──────────┐    ┌──────────────────────────────────────────────────────┐
│ Routing  │    │                 FRAUD ENGINE                          │
│ Service  │    │                                                        │
│          │    │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────┐ │
│          │    │  │ Velocity │  │  Amount  │  │   Geo    │  │Block│ │
│          │    │  │  Check   │  │Threshold │  │ Anomaly  │  │List │ │
│          │    │  └────┬─────┘  └────┬─────┘  └────┬─────┘  └──┬──┘ │
└────┬─────┘    │       │             │             │            │     │
     │          │       ▼             ▼             ▼            ▼     │
     │          │  ┌──────────────────────────────────────────────┐   │
     │ Score?   │  │           Score Aggregator                   │   │
     │─────────►│  │  Velocity: 15 + Amount: 5 + Geo: 5 + BL: 0 │   │
     │          │  │  Total Score: 25/100                         │   │
     │          │  │                                               │   │
     │          │  │  Thresholds:                                  │   │
     │          │  │    0-30:  PASS (proceed)                      │   │
     │          │  │   31-60:  REVIEW (proceed + flag)             │   │
     │          │  │   61-100: BLOCK (decline immediately)         │   │
     │          │  └──────────────────────────────────────────────┘   │
     │          │                                                        │
     │ PASS(25) │                                                        │
     │◄─────────│                                                        │
     │          └──────────────────────────────────────────────────────┘
```

**Fraud Rules Detail:**
| Rule | Score | Trigger Condition |
|------|-------|-------------------|
| Velocity (card) | 0-30 | >5 txns/min same card |
| Velocity (IP) | 0-20 | >10 txns/min same IP |
| Amount spike | 0-25 | 5x average txn amount |
| Geo mismatch | 0-15 | IP country ≠ card country |
| Blocklist match | 100 | Card/email in blocklist |
| First transaction | 5 | New card, no history |
| Night transaction | 5 | Transaction 2-5 AM local |

---

### 10. Token Refresh Flow

```
┌────────┐    ┌─────────┐    ┌──────────┐    ┌──────────┐
│ Client │    │API Gate │    │ Identity │    │PostgreSQL│
│        │    │  way    │    │ Service  │    │          │
└───┬────┘    └────┬────┘    └────┬─────┘    └────┬─────┘
    │              │              │               │
    │ POST /api/v1/auth/refresh   │               │
    │ { refreshToken: "uuid..." } │               │
    │─────────────►│              │               │
    │              │              │               │
    │              │ No JWT needed (public endpoint)              │
    │              │─────────────►│               │
    │              │              │               │
    │              │              │ Find token in DB (not revoked)│
    │              │              │──────────────►│               │
    │              │              │◄──────────────│               │
    │              │              │               │
    │              │              │ Check expiry (7 days)         │
    │              │              │──────┐        │               │
    │              │              │      │        │               │
    │              │              │◄─────┘        │               │
    │              │              │               │
    │              │              │ REVOKE old token (rotation)   │
    │              │              │──────────────►│               │
    │              │              │◄──────────────│               │
    │              │              │               │
    │              │              │ Generate new JWT + refresh    │
    │              │              │──────┐        │               │
    │              │              │      │        │               │
    │              │              │◄─────┘        │               │
    │              │              │               │
    │              │              │ Save new refresh token        │
    │              │              │──────────────►│               │
    │              │              │◄──────────────│               │
    │              │              │               │
    │ 200 OK       │              │               │
    │ { newAccessToken, newRefreshToken, expiresIn }             │
    │◄─────────────│◄─────────────│               │
```

**Token Rotation Security:**
```
Why rotate refresh tokens?
─────────────────────────
1. Old refresh token is REVOKED immediately
2. If attacker steals old token → it's already invalid
3. Each refresh token can only be used ONCE
4. Detects theft: if revoked token is used → alert + revoke all

Timeline:
─────────
t=0:    User logs in → gets AccessToken(15min) + RefreshToken_A(7d)
t=14m:  AccessToken about to expire
t=15m:  Client sends RefreshToken_A
        → RefreshToken_A REVOKED
        → New AccessToken(15min) + RefreshToken_B(7d) issued
t=30m:  Client sends RefreshToken_B
        → RefreshToken_B REVOKED
        → New AccessToken(15min) + RefreshToken_C(7d) issued
```

---

## 🔗 Spring Cloud Filter Chain

### API Gateway Request Processing Pipeline

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     API GATEWAY FILTER CHAIN                              │
│                  (Spring Cloud Gateway WebFlux)                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  Incoming Request                                                         │
│       │                                                                   │
│       ▼                                                                   │
│  ┌──────────────────────────────────┐                                    │
│  │  1. RequestLoggingFilter (PRE)   │  Order: -2 (highest priority)      │
│  │  • Log method, URI, headers      │                                    │
│  │  • Generate correlation ID       │                                    │
│  │  • Start timer for latency       │                                    │
│  └──────────────┬───────────────────┘                                    │
│                 │                                                          │
│                 ▼                                                          │
│  ┌──────────────────────────────────┐                                    │
│  │  2. RateLimitFilter (PRE)        │  Order: -1                         │
│  │  • Extract client identifier     │                                    │
│  │  • Check Redis token bucket      │                                    │
│  │  • 429 if limit exceeded         │                                    │
│  │  • Add X-RateLimit-* headers     │                                    │
│  └──────────────┬───────────────────┘                                    │
│                 │                                                          │
│                 ▼                                                          │
│  ┌──────────────────────────────────┐                                    │
│  │  3. JwtValidationFilter (PRE)    │  Order: 0                          │
│  │  • Skip for public routes        │                                    │
│  │  • Extract Bearer token          │                                    │
│  │  • Validate signature + expiry   │                                    │
│  │  • Add X-User-Id, X-User-Role   │                                    │
│  │  • 401 if invalid                │                                    │
│  └──────────────┬───────────────────┘                                    │
│                 │                                                          │
│                 ▼                                                          │
│  ┌──────────────────────────────────┐                                    │
│  │  4. ApiKeyValidationFilter (PRE) │  Order: 1                          │
│  │  • Check X-API-Key header        │                                    │
│  │  • SHA-256 hash and lookup       │                                    │
│  │  • Validate merchant active      │                                    │
│  │  • Add X-Merchant-Id header      │                                    │
│  │  • 401 if invalid key            │                                    │
│  └──────────────┬───────────────────┘                                    │
│                 │                                                          │
│                 ▼                                                          │
│  ┌──────────────────────────────────┐                                    │
│  │  5. Route to Downstream Service  │                                    │
│  │  • Eureka service discovery      │                                    │
│  │  • Load balancing (Round Robin)  │                                    │
│  │  • Timeout: 30 seconds           │                                    │
│  └──────────────┬───────────────────┘                                    │
│                 │                                                          │
│                 ▼                                                          │
│  ┌──────────────────────────────────┐                                    │
│  │  6. RequestLoggingFilter (POST)  │  Response logging                  │
│  │  • Log status code               │                                    │
│  │  • Log response time             │                                    │
│  │  • Emit metrics to Actuator      │                                    │
│  └──────────────────────────────────┘                                    │
│                                                                           │
└─────────────────────────────────────────────────────────────────────────┘
```

### Route Configuration

| Route ID | Path Pattern | Target Service | Filters Applied |
|----------|-------------|----------------|-----------------|
| identity-public | /api/v1/auth/** | identity-service | RequestLogging, RateLimit |
| identity-secured | /api/v1/users/** | identity-service | All filters |
| merchant-api | /api/v1/merchants/** | merchant-service | All filters |
| payment-api | /api/v1/payments/** | payment-service | All filters |
| payment-sdk | /api/v1/sdk/** | payment-service | RequestLogging, RateLimit, ApiKey |

---

## 🐳 Docker Compose Architecture

### Container Network Topology

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         docker-compose.yml                                        │
│                      Network: payflow-network                                     │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                   │
│  ┌─────────────────────── Infrastructure Layer ──────────────────────────────┐  │
│  │                                                                             │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │  │
│  │  │  PostgreSQL  │  │    Redis     │  │    Kafka     │  │  Zookeeper   │ │  │
│  │  │  Port: 5432  │  │  Port: 6379  │  │  Port: 9092  │  │  Port: 2181  │ │  │
│  │  │              │  │              │  │              │  │              │ │  │
│  │  │ Databases:   │  │ Used for:    │  │ Topics:      │  │ Manages:     │ │  │
│  │  │ • identity   │  │ • Rate limit │  │ • payment.*  │  │ • Kafka      │ │  │
│  │  │ • merchant   │  │ • JWT cache  │  │ • merchant.* │  │   brokers    │ │  │
│  │  │ • payment    │  │ • Idempotency│  │ • settlement │  │              │ │  │
│  │  │ • routing    │  │ • Sessions   │  │ • notification│  │              │ │  │
│  │  │ • settlement │  │              │  │              │  │              │ │  │
│  │  │ • notification│  │              │  │              │  │              │ │  │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘ │  │
│  │                                                                             │  │
│  └─────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                   │
│  ┌─────────────────────── Spring Cloud Layer ────────────────────────────────┐  │
│  │                                                                             │  │
│  │  ┌──────────────┐  ┌──────────────┐                                      │  │
│  │  │   Eureka     │  │   Config     │                                      │  │
│  │  │  Port: 8761  │  │  Port: 8888  │                                      │  │
│  │  │              │  │              │                                      │  │
│  │  │ All services │  │ Git-backed   │                                      │  │
│  │  │ register here│  │ YAML configs │                                      │  │
│  │  └──────────────┘  └──────────────┘                                      │  │
│  │                                                                             │  │
│  └─────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                   │
│  ┌─────────────────────── Application Layer ─────────────────────────────────┐  │
│  │                                                                             │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐       │  │
│  │  │API Gate  │ │Identity  │ │Merchant  │ │Payment   │ │Routing   │       │  │
│  │  │Port:8080 │ │Port:8081 │ │Port:8082 │ │Port:8083 │ │Port:8084 │       │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘       │  │
│  │                                                                             │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐                    │  │
│  │  │Settle    │ │Webhook   │ │Notifica  │ │Bank Sim  │                    │  │
│  │  │Port:8085 │ │Port:8086 │ │Port:8087 │ │Port:9090 │                    │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘                    │  │
│  │                                                                             │  │
│  └─────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                   │
│  ┌─────────────────────── Frontend Layer ────────────────────────────────────┐  │
│  │                                                                             │  │
│  │  ┌──────────────────┐  ┌──────────────────┐                              │  │
│  │  │ Merchant Portal  │  │ Hosted Checkout   │                              │  │
│  │  │   Port: 5173     │  │   Port: 5174      │                              │  │
│  │  │ (Vite dev server)│  │ (Vite dev server) │                              │  │
│  │  └──────────────────┘  └──────────────────┘                              │  │
│  │                                                                             │  │
│  └─────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                   │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Container Startup Order (depends_on)

```
Level 0 (No dependencies):     PostgreSQL, Redis, Zookeeper
Level 1 (Infra ready):         Kafka (needs Zookeeper)
Level 2 (Messaging ready):     Eureka, Config Server
Level 3 (Discovery ready):     All application services
Level 4 (Services ready):      API Gateway, Frontends
```

### Health Check Configuration

| Container | Health Check | Interval | Retries | Start Period |
|-----------|-------------|----------|---------|--------------|
| PostgreSQL | `pg_isready -U postgres` | 10s | 5 | 30s |
| Redis | `redis-cli ping` | 10s | 3 | 10s |
| Kafka | Topic creation test | 30s | 5 | 60s |
| Eureka | `/actuator/health` | 15s | 5 | 45s |
| Config Server | `/actuator/health` | 15s | 5 | 30s |
| All Services | `/actuator/health` | 20s | 5 | 60s |

---

## 🔄 Data Flow Patterns

### Pattern 1: Synchronous Request-Response (Payment Authorization)

```
Client → Gateway → Service → Database → Response
         ↕ Redis    ↕ Feign
         (cache)    (inter-service)
```

**When to use:** Real-time user-facing operations where latency matters.

### Pattern 2: Asynchronous Event-Driven (Settlement)

```
Event Trigger → Kafka → Consumer → Process → Database
    (Cron)              (Batch)     (Calculate)
```

**When to use:** Background processing, eventual consistency acceptable.

### Pattern 3: Request + Async Side-Effect (Payment + Webhook)

```
Client → Service → Database → Response (sync)
                        ↓
                   Kafka Event → Webhook Service → Merchant (async)
                              → Notification Service → Email (async)
```

**When to use:** Primary operation must be fast, side-effects can be eventual.

---

## 💥 Failure Modes and Recovery

### Circuit Breaker States (Resilience4j)

```
┌──────────────────────────────────────────────────────────────────┐
│                    CIRCUIT BREAKER STATES                          │
├──────────────────────────────────────────────────────────────────┤
│                                                                    │
│  ┌────────┐    failure rate    ┌────────┐    wait duration    ┌────────┐
│  │ CLOSED │    ≥ 50%           │  OPEN  │    expires          │ HALF-  │
│  │        │───────────────────►│        │───────────────────►│  OPEN  │
│  │(normal)│                    │(reject)│                    │(probe) │
│  └────────┘                    └────────┘                    └───┬────┘
│       ▲                                                          │
│       │                              success                     │
│       └──────────────────────────────────────────────────────────┘
│                                      │
│       ▲                              │ failure
│       │                              ▼
│       │                         ┌────────┐
│       │                         │  OPEN  │
│       └─────────────────────────┤(reject)│
│            wait duration        └────────┘
│                                                                    │
│  Configuration (PayFlow):                                          │
│  • Failure rate threshold: 50%                                    │
│  • Slow call duration: 3 seconds                                  │
│  • Wait duration in open state: 30 seconds                        │
│  • Permitted calls in half-open: 5                                │
│  • Sliding window size: 10 calls                                  │
│                                                                    │
└──────────────────────────────────────────────────────────────────┘
```

### Failure Recovery Matrix

| Failure Type | Detection | Recovery | Data Impact |
|-------------|-----------|----------|-------------|
| Service crash | Eureka heartbeat miss | Auto-restart (Docker) | None (stateless) |
| Database down | Connection pool exhausted | Failover to replica | RPO = 0 (sync replication) |
| Kafka broker down | Producer timeout | Retry + local buffer | Events delayed |
| Network partition | Request timeout | Circuit breaker opens | Stale data possible |
| Bank timeout | TCP timeout (30s) | Retry on backup acquirer | Transaction in limbo |
| Redis down | Cache miss | Bypass cache, hit DB | Slight latency increase |
| OOM kill | Container health check | Restart with memory limit | Transaction may need replay |

---

## 📝 What You Learned

| # | Concept | Key Takeaway |
|---|---------|--------------|
| 1 | Architecture Layers | 4-tier: Client → Edge → Service → Data |
| 2 | Sync vs Async | Payments sync, side-effects async via Kafka |
| 3 | Filter Chain | Gateway processes requests through ordered filter pipeline |
| 4 | Sequence Diagrams | Every flow has happy path + failure modes documented |
| 5 | Docker Compose | Infrastructure → Spring Cloud → Apps → Frontends startup order |
| 6 | Circuit Breaker | Resilience4j prevents cascade failures between services |
| 7 | Settlement | Batch processing with fee calculation at day end |
| 8 | Webhook Delivery | Exponential backoff with 5 retry attempts over 24 hours |
| 9 | Smart Routing | Multi-factor scoring: BIN match, success rate, cost, load |
| 10 | Fraud Detection | Rule-based scoring with configurable thresholds |
| 11 | Token Rotation | Old refresh tokens revoked immediately on use |
| 12 | Idempotency | Every mutation checked via Redis before processing |

---

## 📚 Document Index

| # | Document | Description |
|---|----------|-------------|
| 0.1 | [phase0-part1-project-overview.md](./phase0-part1-project-overview.md) | Project overview and goals |
| 0.2 | [phase0-part2-environment-setup.md](./phase0-part2-environment-setup.md) | Dev environment setup |
| 1 | [phase1-system-design.md](./phase1-system-design.md) | System design decisions |
| 2 | [phase2-high-level-design.md](./phase2-high-level-design.md) | **This document** — HLD |
| 3 | [phase3-low-level-design.md](./phase3-low-level-design.md) | Low-level design details |
| 4 | [system-design-complete-guide.md](./system-design-complete-guide.md) | System design concepts |
| 5 | [system-design-interview-cheatsheet.md](./system-design-interview-cheatsheet.md) | Interview prep |

---

## 🚀 Next Steps

1. **Phase 3** — Dive into low-level design: entity classes, repository interfaces, service signatures
2. **Phase 4** — Start coding each microservice following the sequences above
3. **Testing** — Write integration tests that verify each sequence diagram's happy path
4. **Load Testing** — Validate NFRs with Gatling scripts simulating 1000 TPS

---

*"Good architecture makes the system easy to understand, easy to develop, easy to maintain, and easy to deploy."* — Robert C. Martin
