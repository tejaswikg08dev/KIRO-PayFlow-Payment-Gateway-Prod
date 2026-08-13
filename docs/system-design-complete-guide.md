# 📘 System Design Complete Guide — PayFlow Edition

> **"Every system design concept, explained with a real PayFlow Payment Gateway example."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | Reference Guide |
| **Previous** | [phase3-low-level-design.md](./phase3-low-level-design.md) |
| **Next** | [system-design-interview-cheatsheet.md](./system-design-interview-cheatsheet.md) |
| **Author** | Tejaswi |
| **Created** | 2024 |
| **Status** | Living Document |

---

## 📖 Table of Contents

1. [Scalability](#1-scalability)
2. [Load Balancing](#2-load-balancing)
3. [Caching](#3-caching)
4. [Database Selection](#4-database-selection)
5. [Database Scaling](#5-database-scaling)
6. [Message Queues](#6-message-queues)
7. [Microservices](#7-microservices)
8. [API Design](#8-api-design)
9. [Authentication & Authorization](#9-authentication--authorization)
10. [Consistency Models](#10-consistency-models)
11. [Distributed Transactions](#11-distributed-transactions)
12. [Idempotency](#12-idempotency)
13. [Rate Limiting](#13-rate-limiting)
14. [Circuit Breaker](#14-circuit-breaker)
15. [Event-Driven Architecture](#15-event-driven-architecture)
16. [Batch Processing](#16-batch-processing)
17. [Security](#17-security)
18. [Monitoring & Observability](#18-monitoring--observability)
19. [CI/CD](#19-cicd)
20. [Disaster Recovery](#20-disaster-recovery)

---

## 1. Scalability

### Concept

Scalability is a system's ability to handle increased load by adding resources.

```
VERTICAL SCALING (Scale Up)        HORIZONTAL SCALING (Scale Out)
┌────────────────────┐             ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐
│                    │             │ App │ │ App │ │ App │ │ App │
│   Bigger Server    │             │  1  │ │  2  │ │  3  │ │  4  │
│   64 CPU / 256GB   │             └──┬──┘ └──┬──┘ └──┬──┘ └──┬──┘
│                    │                │       │       │       │
│   Single point of  │                └───────┴───────┴───────┘
│   failure          │                        │
└────────────────────┘                   Load Balancer
```

### PayFlow Example

```
PayFlow Horizontal Scaling Strategy:
─────────────────────────────────────
                    ┌──────────────────┐
                    │  API Gateway x3  │  ← Stateless, scale freely
                    └────────┬─────────┘
                             │
         ┌───────────────────┼───────────────────┐
         │                   │                    │
    ┌────▼────┐        ┌────▼────┐         ┌────▼────┐
    │Payment  │        │Payment  │         │Payment  │
    │Service  │        │Service  │         │Service  │
    │Instance1│        │Instance2│         │Instance3│
    └────┬────┘        └────┬────┘         └────┬────┘
         │                   │                    │
         └───────────────────┼───────────────────┘
                             │
                    ┌────────▼─────────┐
                    │  PostgreSQL      │
                    │  (Primary +      │
                    │   Read Replicas) │
                    └──────────────────┘

Why this works:
• Services are STATELESS → any instance can handle any request
• State lives in PostgreSQL + Redis (shared)
• Eureka handles discovery of new instances
• Gateway load-balances across instances
```

---

## 2. Load Balancing

### Concept

Distributes incoming requests across multiple service instances.

### PayFlow Example

```
┌─────────────────────────────────────────────────────────┐
│              LOAD BALANCING IN PAYFLOW                    │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  Layer 1: External (AWS ALB)                             │
│  ┌─────────────────────────────────────────────┐        │
│  │  Client → ALB → API Gateway instances       │        │
│  │  Algorithm: Round Robin                       │        │
│  │  Health Check: /actuator/health every 30s    │        │
│  └─────────────────────────────────────────────┘        │
│                                                           │
│  Layer 2: Internal (Spring Cloud LoadBalancer)            │
│  ┌─────────────────────────────────────────────┐        │
│  │  Gateway → Service instances via Eureka     │        │
│  │  Algorithm: Round Robin (default)            │        │
│  │  Discovery: Eureka registry lookup           │        │
│  └─────────────────────────────────────────────┘        │
│                                                           │
│  Layer 3: Database (pgpool/pgbouncer)                    │
│  ┌─────────────────────────────────────────────┐        │
│  │  Service → DB connection pool               │        │
│  │  Writes: Primary only                        │        │
│  │  Reads: Distributed to replicas              │        │
│  └─────────────────────────────────────────────┘        │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

---

## 3. Caching

### Concept

Store frequently accessed data in fast storage (RAM) to reduce database load.

### PayFlow Example

```
┌─────────────────────────────────────────────────────────────┐
│                  REDIS CACHING IN PAYFLOW                     │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  Cache Use Cases:                                            │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ 1. Rate Limiting     → Token Bucket counters (TTL=60s)│  │
│  │ 2. JWT Validation    → Cached user claims (TTL=15min) │  │
│  │ 3. API Key Lookup    → Hash → MerchantId (TTL=5min)  │  │
│  │ 4. Idempotency Keys  → Key → Response JSON (TTL=24h) │  │
│  │ 5. Merchant Config   → MerchantId → Config (TTL=10m) │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                               │
│  Cache-Aside Pattern (Used for API Key validation):          │
│                                                               │
│  ┌────────┐    miss    ┌───────┐    query    ┌──────────┐  │
│  │ Gateway │──────────►│ Redis │             │PostgreSQL│  │
│  │         │           │       │◄────────────│          │  │
│  │         │◄──────────│(empty)│────────────►│(api_keys)│  │
│  │         │    hit    │       │   populate   │          │  │
│  └────────┘           └───────┘              └──────────┘  │
│                                                               │
│  Key Structure:                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ rate:limit:{clientIp}         → counter (integer)     │  │
│  │ idempotency:{key}             → response (JSON)       │  │
│  │ api:key:{sha256Hash}          → merchantId (string)   │  │
│  │ jwt:blacklist:{tokenId}       → 1 (boolean)           │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. Database Selection

### Concept

Choose the right database for the right use case.

### PayFlow Example

| Use Case | Database | Reason |
|----------|----------|--------|
| Users, Merchants, Payments | PostgreSQL | ACID transactions, relational data, strong consistency |
| Rate limiting, Caching | Redis | In-memory speed, TTL support, atomic operations |
| Webhook delivery logs | DynamoDB | High write throughput, TTL auto-cleanup, schemaless |
| Event streaming | Kafka (log) | Ordered, durable, replayable event log |
| Search/Analytics (future) | Elasticsearch | Full-text search, aggregation queries |

```
Decision Matrix:
─────────────────────────────────────────────────────────
│ Question                          │ Answer → Database │
├───────────────────────────────────┼───────────────────┤
│ Need ACID transactions?           │ Yes → PostgreSQL  │
│ Need sub-millisecond latency?     │ Yes → Redis       │
│ Need schema flexibility?          │ Yes → DynamoDB    │
│ Need event replay?                │ Yes → Kafka       │
│ Need full-text search?            │ Yes → Elasticsearch│
│ Need time-series data?            │ Yes → TimescaleDB │
─────────────────────────────────────────────────────────
```

---

## 5. Database Scaling

### PayFlow Example

```
┌─────────────────────────────────────────────────────────────────┐
│               DATABASE SCALING STRATEGY                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Strategy 1: Read Replicas (Reads heavy)                         │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                                                              │ │
│  │   ┌──────────┐     ┌──────────┐     ┌──────────┐         │ │
│  │   │ Primary  │────►│ Replica1 │     │ Replica2 │         │ │
│  │   │ (Writes) │     │ (Reads)  │     │ (Reads)  │         │ │
│  │   └──────────┘     └──────────┘     └──────────┘         │ │
│  │                                                              │ │
│  │   PayFlow: Dashboard queries hit replicas                   │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  Strategy 2: Database-per-Service (Microservices)                │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                                                              │ │
│  │   Identity → payflow_identity (users, tokens)               │ │
│  │   Merchant → payflow_merchant (merchants, api_keys)         │ │
│  │   Payment  → payflow_payment  (orders, payments, refunds)   │ │
│  │   Routing  → payflow_routing  (routes, fraud_rules)         │ │
│  │                                                              │ │
│  │   Each service owns its data, no shared tables              │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  Strategy 3: Partitioning (future, high volume)                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                                                              │ │
│  │   payments table partitioned by created_at (monthly)        │ │
│  │   orders table partitioned by merchant_id (hash)            │ │
│  │                                                              │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. Message Queues

### PayFlow Example

```
┌─────────────────────────────────────────────────────────────────┐
│                  KAFKA IN PAYFLOW                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Topic: payment.events (3 partitions, replication-factor=3)      │
│                                                                   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  Partition 0: [msg1] [msg4] [msg7] ...                   │   │
│  │  Partition 1: [msg2] [msg5] [msg8] ...                   │   │
│  │  Partition 2: [msg3] [msg6] [msg9] ...                   │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                   │
│  Partitioning Key: merchantId (ensures order per merchant)       │
│                                                                   │
│  Producers:          Consumers:                                   │
│  ─────────           ──────────                                   │
│  Payment Service →   Webhook Service (group: webhook-consumer)   │
│                  →   Notification Service (group: notif-consumer) │
│                  →   Settlement Service (group: settle-consumer)  │
│                                                                   │
│  Why Kafka (not RabbitMQ)?                                       │
│  • Message replay: can re-read events for debugging              │
│  • Ordering guarantee per partition                              │
│  • High throughput: 100K+ messages/sec                           │
│  • Consumer groups: multiple independent consumers              │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 7. Microservices

### PayFlow Example

```
Why 11 microservices (not monolith)?
─────────────────────────────────────
┌────────────────────────────────────────────────┐
│ Reason          │ PayFlow Application           │
├─────────────────┼──────────────────────────────┤
│ Independent     │ Deploy payment-service fix   │
│ deployment      │ without touching identity    │
├─────────────────┼──────────────────────────────┤
│ Tech diversity  │ Routing uses Netty (TCP)     │
│                 │ Gateway uses WebFlux          │
│                 │ Settlement uses Spring Batch  │
├─────────────────┼──────────────────────────────┤
│ Team ownership  │ Each service = 1 team scope  │
├─────────────────┼──────────────────────────────┤
│ Scaling         │ Payment service needs 3x     │
│                 │ instances, others need 1x    │
├─────────────────┼──────────────────────────────┤
│ Fault isolation │ If notification crashes,     │
│                 │ payments still work          │
└────────────────────────────────────────────────┘
```

---

## 8. API Design

### PayFlow Example

```
RESTful API Design Principles Used:
───────────────────────────────────

1. Resource-based URLs:
   POST   /v1/orders              → Create order
   GET    /v1/orders/{id}         → Get order
   POST   /v1/payments/{id}/capture → Action on resource

2. Consistent response envelope:
   {
     "success": true,
     "data": { ... },
     "error": null,
     "timestamp": "2024-01-15T10:30:00Z",
     "requestId": "req_abc123"
   }

3. Error responses:
   {
     "success": false,
     "data": null,
     "error": {
       "code": "PAYMENT_DECLINED",
       "message": "Insufficient funds",
       "details": { "bankResponseCode": "51" }
     }
   }

4. Pagination:
   GET /v1/orders?page=0&size=20&sort=createdAt,desc

5. Versioning: /v1/ prefix for all endpoints
```

---

## 9. Authentication & Authorization

### PayFlow Example

```
┌─────────────────────────────────────────────────────────────────┐
│              DUAL AUTH STRATEGY IN PAYFLOW                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Channel 1: Dashboard Users (JWT)                                │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  Login → JWT (15min) + Refresh Token (7 days)           │   │
│  │  Every request: Authorization: Bearer <jwt>              │   │
│  │  Claims: { sub: userId, role: MERCHANT, email: ... }    │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                   │
│  Channel 2: Server-to-Server API (API Key)                       │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  Header: X-API-Key: pk_live_a1b2c3d4e5...               │   │
│  │  Gateway: SHA-256(key) → lookup in merchant DB          │   │
│  │  Identifies: merchantId, permissions                     │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                   │
│  RBAC (Role-Based Access Control):                               │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  ADMIN    → All endpoints, all merchants                 │   │
│  │  MERCHANT → Own merchant data only                       │   │
│  │  USER     → View only (customer-facing checkout)         │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 10. Consistency Models

### PayFlow Example

```
STRONG CONSISTENCY (Payments):
  Payment state changes MUST be atomic:
  • authorize → update order status + create payment record
  • If DB write fails, NO bank auth code is returned
  • Uses @Transactional to ensure all-or-nothing

EVENTUAL CONSISTENCY (Webhooks):
  After payment is captured:
  1. Payment DB updated ✓ (immediate)
  2. Kafka event published (may have slight delay)
  3. Webhook delivered (seconds later)
  4. Merchant system updated (eventually)

  This is ACCEPTABLE because:
  • The payment is already confirmed in our system
  • Webhook is a notification, not a decision point
  • Merchant can always poll GET /payments/{id} for truth
```

---

## 11. Distributed Transactions

### PayFlow Example — Saga Pattern

```
┌─────────────────────────────────────────────────────────────────┐
│              PAYMENT AUTHORIZATION SAGA                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Step 1: Create Order (Payment Service)                          │
│  Step 2: Check Fraud (Routing Service)                           │
│  Step 3: Authorize at Bank (Routing Service → Bank)              │
│  Step 4: Update Payment Status (Payment Service)                 │
│  Step 5: Publish Event (Kafka)                                   │
│                                                                   │
│  Compensation (if Step 3 fails):                                 │
│  • Step 2 compensate: Log fraud check wasted                    │
│  • Step 1 compensate: Mark order as FAILED                      │
│                                                                   │
│  Implementation: Orchestration-based Saga                        │
│  • PaymentService acts as orchestrator                           │
│  • Calls Routing Service via Feign (sync)                       │
│  • On failure: updates order to FAILED + publishes failure event │
│                                                                   │
│  Why NOT 2PC (Two-Phase Commit)?                                 │
│  • Bank simulator is external → can't participate in 2PC        │
│  • Kafka doesn't support 2PC                                    │
│  • Too slow for payment latency requirements                    │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 12. Idempotency

### PayFlow Example

```
┌─────────────────────────────────────────────────────────────────┐
│              IDEMPOTENCY IN PAYFLOW                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Header: X-Idempotency-Key: idem_merchant123_order456            │
│                                                                   │
│  Flow:                                                            │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                                                              │ │
│  │  Request arrives with idempotency key                       │ │
│  │       │                                                      │ │
│  │       ▼                                                      │ │
│  │  ┌──────────────┐                                           │ │
│  │  │ Redis: EXISTS │                                           │ │
│  │  │ key?          │                                           │ │
│  │  └──────┬───────┘                                           │ │
│  │         │                                                    │ │
│  │    YES  │  NO                                                │ │
│  │    │    │                                                    │ │
│  │    │    ▼                                                    │ │
│  │    │  ┌──────────────┐                                      │ │
│  │    │  │ Acquire Lock │ (SET key NX EX 30)                   │ │
│  │    │  └──────┬───────┘                                      │ │
│  │    │         │                                               │ │
│  │    │         ▼                                               │ │
│  │    │  ┌──────────────┐                                      │ │
│  │    │  │ Process      │                                      │ │
│  │    │  │ Payment      │                                      │ │
│  │    │  └──────┬───────┘                                      │ │
│  │    │         │                                               │ │
│  │    │         ▼                                               │ │
│  │    │  ┌──────────────┐                                      │ │
│  │    │  │ Store Result │ (SET key response EX 86400)          │ │
│  │    │  └──────┬───────┘                                      │ │
│  │    │         │                                               │ │
│  │    ▼         ▼                                               │ │
│  │  ┌────────────────┐                                         │ │
│  │  │ Return Response │ ← Same response every time             │ │
│  │  └────────────────┘                                         │ │
│  │                                                              │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 13. Rate Limiting

### PayFlow Example

```
Token Bucket Algorithm (Redis-based):
─────────────────────────────────────

Bucket: 100 tokens, refill 100 tokens/minute

Request 1:  tokens=99 ✓  (consume 1)
Request 2:  tokens=98 ✓
...
Request 100: tokens=0  ✓
Request 101: tokens=0  ✗ → 429 Too Many Requests

After 60s: tokens refilled to 100

Rate Limits per Tier:
┌──────────────────────────────────────────┐
│ Tier        │ Limit         │ Window     │
├─────────────┼───────────────┼────────────┤
│ Free        │ 100 req/min   │ Per API key│
│ Standard    │ 1000 req/min  │ Per API key│
│ Enterprise  │ 10000 req/min │ Per API key│
│ Per IP      │ 50 req/min    │ Per IP     │
└──────────────────────────────────────────┘

Response Headers:
  X-RateLimit-Limit: 100
  X-RateLimit-Remaining: 45
  X-RateLimit-Reset: 1705312800
```

---

## 14. Circuit Breaker

### PayFlow Example

```
Routing Service → Bank Simulator Communication:
────────────────────────────────────────────────

Normal state (CLOSED):
  Every payment request goes to bank → ~200ms response

Bank starts failing (50% error rate):
  Circuit OPENS → requests fail fast (no bank call)
  Fallback: Return "Payment processing delayed, try again later"

After 30 seconds (HALF-OPEN):
  Allow 5 probe requests through to bank
  If 3/5 succeed → circuit CLOSES (back to normal)
  If 3/5 fail → circuit stays OPEN (wait another 30s)

Config in PayFlow:
  resilience4j:
    circuitbreaker:
      instances:
        bankSimulator:
          failureRateThreshold: 50
          slowCallDurationThreshold: 3s
          waitDurationInOpenState: 30s
          permittedNumberOfCallsInHalfOpenState: 5
          slidingWindowSize: 10
```

---

## 15. Event-Driven Architecture

### PayFlow Example

```
Event Flow After Successful Payment:
─────────────────────────────────────

Payment Captured
      │
      ▼ Kafka: payment.events
      │
      ├──► Webhook Service
      │      → POST to merchant's webhook URL
      │      → Retry on failure (exponential backoff)
      │
      ├──► Notification Service
      │      → Send email to customer
      │      → Send SMS confirmation
      │
      └──► Settlement Service
             → Add to day's settlement batch
             → Calculate merchant fees

Benefits:
• Payment Service doesn't KNOW about webhooks/email
• Adding a new consumer (e.g., analytics) = zero changes to producer
• Each consumer processes at its own pace
• Failed consumer doesn't affect others
```

---

## 16. Batch Processing

### PayFlow Example — Settlement

```
Spring Batch Job: DailySettlementJob
─────────────────────────────────────

┌─────────────────────────────────────────────────────────────┐
│  Step 1: Reader                                              │
│  • Query: SELECT * FROM payments                            │
│           WHERE status = 'CAPTURED'                          │
│           AND created_at BETWEEN today_start AND today_end   │
│  • Chunk size: 100 payments                                  │
├─────────────────────────────────────────────────────────────┤
│  Step 2: Processor                                           │
│  • Group payments by merchant_id                            │
│  • For each merchant:                                        │
│    - Sum captured amounts                                    │
│    - Fetch fee config (platform %, GST %)                   │
│    - Calculate: net = total - (total × fee%) - GST          │
├─────────────────────────────────────────────────────────────┤
│  Step 3: Writer                                              │
│  • INSERT settlement records                                 │
│  • UPDATE payments SET status = 'SETTLED'                   │
│  • Publish settlement.completed event                       │
├─────────────────────────────────────────────────────────────┤
│  Schedule: @Scheduled(cron = "0 59 23 * * *")               │
│            Runs at 23:59 IST daily                           │
└─────────────────────────────────────────────────────────────┘
```

---

## 17. Security

### PayFlow Security Layers

```
┌─────────────────────────────────────────────────────────────────┐
│                    SECURITY IN DEPTH                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Layer 1: Network                                                │
│  • VPC with private subnets for services                        │
│  • Security groups: only gateway exposed to internet            │
│  • TLS/HTTPS everywhere                                         │
│                                                                   │
│  Layer 2: API Gateway                                            │
│  • Rate limiting (DDoS protection)                              │
│  • JWT validation                                                │
│  • API key validation                                            │
│  • CORS configuration                                            │
│                                                                   │
│  Layer 3: Application                                            │
│  • BCrypt password hashing (cost=12)                            │
│  • Input validation (@Valid on all DTOs)                        │
│  • SQL injection prevention (JPA parameterized queries)         │
│  • XSS prevention (output encoding)                             │
│                                                                   │
│  Layer 4: Data                                                   │
│  • Encryption at rest (AWS RDS encryption)                      │
│  • Card data: only last 4 digits stored (PCI compliance)        │
│  • API keys: only SHA-256 hash stored                           │
│  • Webhook secrets: encrypted in DB                             │
│                                                                   │
│  Layer 5: Audit                                                  │
│  • Every state change logged with actor + timestamp             │
│  • Kafka event log is immutable audit trail                     │
│  • Request/response logging (without PII)                       │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 18. Monitoring & Observability

### PayFlow Observability Stack

```
Three Pillars:
─────────────

1. METRICS (Prometheus + Grafana)
   • Payment success rate
   • p99 latency per endpoint
   • Active connections
   • Kafka consumer lag
   • Circuit breaker state

2. LOGS (ELK Stack / CloudWatch)
   • Structured JSON logs
   • Correlation ID across services
   • Request/response logging
   • Error stack traces

3. TRACES (Micrometer + Zipkin)
   • End-to-end request flow
   • Time spent in each service
   • Database query time
   • External API call duration

Key Metrics Dashboard:
┌──────────────────────────────────────────────┐
│  Payment Success Rate:  98.5%  ↑ 0.2%       │
│  p99 Latency:          450ms   ↓ 50ms       │
│  Active Orders:        1,234                  │
│  TPS (current):        156                    │
│  Circuit Breakers:     All CLOSED ✓          │
│  Kafka Consumer Lag:   12 messages           │
└──────────────────────────────────────────────┘
```

---

## 19. CI/CD

### PayFlow Pipeline

```
┌─────────────────────────────────────────────────────────────────┐
│                    CI/CD PIPELINE                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌────┐    ┌──────┐    ┌──────┐    ┌──────┐    ┌─────────┐   │
│  │Push│───►│Build │───►│Test  │───►│Docker│───►│  Deploy  │   │
│  │    │    │(Maven)│    │(JUnit)│    │Build │    │  (ECS)   │   │
│  └────┘    └──────┘    └──────┘    └──────┘    └─────────┘   │
│                                                                   │
│  GitHub Actions Workflow:                                         │
│  • Trigger: Push to main, PR to main                            │
│  • Build: mvn clean package -DskipTests                         │
│  • Test: mvn test (JUnit 5 + Testcontainers)                   │
│  • Coverage: JaCoCo minimum 80%                                 │
│  • Docker: Multi-stage build → ECR push                         │
│  • Deploy: ECS service update with new image                    │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 20. Disaster Recovery

### PayFlow DR Strategy

```
┌─────────────────────────────────────────────────────────────────┐
│                    DISASTER RECOVERY PLAN                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  RPO (Recovery Point Objective): 0 — No data loss               │
│  RTO (Recovery Time Objective): < 5 minutes                      │
│                                                                   │
│  Strategy: Active-Passive with automated failover               │
│                                                                   │
│  Database:                                                        │
│  • PostgreSQL: Synchronous replication to standby               │
│  • Automated failover via RDS Multi-AZ                          │
│  • Point-in-time recovery: up to 35 days                        │
│                                                                   │
│  Application:                                                     │
│  • Containers: Auto-restart on crash (ECS)                      │
│  • Health checks: Failed → new task launched in 30s             │
│  • Multi-AZ deployment: survive AZ failure                      │
│                                                                   │
│  Kafka:                                                           │
│  • Replication factor: 3                                         │
│  • Min in-sync replicas: 2                                       │
│  • Survive: 1 broker failure                                     │
│                                                                   │
│  Backup Strategy:                                                │
│  • DB snapshots: Daily automated                                │
│  • Kafka: Log retention 7 days (replayable)                     │
│  • Config: Git-backed (always recoverable)                      │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 📝 What You Learned

| # | Concept | PayFlow Application |
|---|---------|--------------------|
| 1 | Scalability | Stateless services + shared state in DB/Redis |
| 2 | Load Balancing | ALB (external) + Eureka (internal) |
| 3 | Caching | Redis for rate limits, idempotency, JWT |
| 4 | DB Selection | PostgreSQL for ACID, Redis for speed, DynamoDB for logs |
| 5 | DB Scaling | Database-per-service, read replicas |
| 6 | Message Queues | Kafka for async events (webhooks, notifications) |
| 7 | Microservices | 11 services, each independently deployable |
| 8 | API Design | RESTful, versioned, consistent error format |
| 9 | Auth | JWT for dashboards, API keys for server-to-server |
| 10 | Consistency | Strong for payments, eventual for notifications |
| 11 | Distributed Txns | Saga pattern with orchestration |
| 12 | Idempotency | Redis-backed with TTL 24h |
| 13 | Rate Limiting | Token bucket per API key |
| 14 | Circuit Breaker | Resilience4j on bank communication |
| 15 | Event-Driven | Kafka pub/sub for decoupled side-effects |
| 16 | Batch Processing | Spring Batch for daily settlement |
| 17 | Security | Defense in depth: network → gateway → app → data |
| 18 | Monitoring | Prometheus metrics + structured logs + traces |
| 19 | CI/CD | GitHub Actions → Docker → ECR → ECS |
| 20 | Disaster Recovery | RPO=0, RTO<5min, Multi-AZ + replication |

---

## 📚 Document Index

| # | Document | Description |
|---|----------|-------------|
| 1 | [phase1-system-design.md](./phase1-system-design.md) | System design decisions |
| 2 | [phase2-high-level-design.md](./phase2-high-level-design.md) | HLD |
| 3 | [phase3-low-level-design.md](./phase3-low-level-design.md) | LLD |
| 4 | [system-design-complete-guide.md](./system-design-complete-guide.md) | **This document** |
| 5 | [system-design-interview-cheatsheet.md](./system-design-interview-cheatsheet.md) | Quick reference |

---

## 🚀 Next Steps

1. Use this guide as a study reference for system design interviews
2. Each concept maps to actual PayFlow code — explore the implementations
3. Practice explaining these concepts using PayFlow as your real-world example

---

*"The best system design is one you can explain in 5 minutes and implement in 5 months."*
