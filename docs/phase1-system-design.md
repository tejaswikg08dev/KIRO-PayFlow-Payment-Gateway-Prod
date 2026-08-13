# 🏗️ Phase 1: System Design

> **"A payment gateway is not just an API — it's a distributed state machine that must never lose money."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 1 — System Design |
| **Previous** | [phase0-part2-environment-setup.md](./phase0-part2-environment-setup.md) |
| **Next** | [phase2-high-level-design.md](./phase2-high-level-design.md) |
| **Author** | Tejaswi |
| **Created** | 2024 |
| **Status** | Living Document |
| **Audience** | System Design Learners, Backend Engineers |

---

## 📖 Table of Contents

1. [Introduction](#introduction)
2. [Functional Requirements](#functional-requirements)
3. [Non-Functional Requirements](#non-functional-requirements)
4. [Design Decisions](#design-decisions)
5. [API Design](#api-design)
6. [Database Design](#database-design)
7. [ISO 8583 Protocol](#iso-8583-protocol)
8. [Kafka Topics Design](#kafka-topics-design)
9. [Security Design](#security-design)
10. [Capacity Planning](#capacity-planning)
11. [What You Learned](#what-you-learned)
12. [Document Index](#document-index)

---

## 🎯 Introduction

### Why System Design Matters for Payment Gateways

Payment gateways are among the most complex distributed systems to design because they must satisfy
seemingly contradictory requirements:

- **Fast** (sub-500ms response) yet **durable** (never lose a transaction)
- **Available** (99.9% uptime) yet **consistent** (double-charging is unacceptable)
- **Simple API** for merchants yet **complex internals** (fraud, routing, settlement)

This document walks through every design decision for PayFlow — a production-grade payment gateway
inspired by Stripe, Razorpay, and Adyen. Each decision includes the reasoning, alternatives considered,
trade-offs, and real-world comparisons.

### What Makes a Payment Gateway Different?

Unlike a typical CRUD application:

```
┌─────────────────────────────────────────────────────────────────────┐
│                    PAYMENT GATEWAY COMPLEXITY                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  1. MONEY IS INVOLVED → Zero tolerance for bugs                      │
│  2. REGULATORY COMPLIANCE → PCI-DSS, RBI guidelines, GDPR           │
│  3. MULTI-PARTY → Merchant + Customer + Bank + Network               │
│  4. ASYNC BY NATURE → Bank responses can take seconds or days        │
│  5. IDEMPOTENCY IS CRITICAL → Retries must not double-charge         │
│  6. AUDIT TRAIL → Every state change must be logged forever          │
│  7. TIME-SENSITIVE → Authorization expires, settlements batch         │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

### The PayFlow Architecture at a Glance

```
                            ┌──────────────┐
                            │   Merchant   │
                            │  Dashboard   │
                            └──────┬───────┘
                                   │ HTTPS
                            ┌──────▼───────┐
                            │  API Gateway │
                            │ (Spring Cloud│
                            │   Gateway)   │
                            └──────┬───────┘
                                   │
              ┌────────────────────┼────────────────────┐
              │                    │                     │
     ┌────────▼──────┐   ┌───────▼───────┐   ┌────────▼──────┐
     │   Identity    │   │    Payment    │   │   Merchant   │
     │   Service     │   │    Service    │   │   Service    │
     └────────┬──────┘   └───────┬───────┘   └────────┬──────┘
              │                   │                     │
              │           ┌───────▼───────┐            │
              │           │   Payment    │            │
              │           │   Router     │            │
              │           └───────┬───────┘            │
              │                   │                     │
              │           ┌───────▼───────┐            │
              │           │    Bank      │            │
              │           │  Simulator   │            │
              │           └───────────────┘            │
              │                                        │
     ┌────────▼──────────────────────────────────▼──────┐
     │                   Apache Kafka                     │
     │            (Event Bus / Message Broker)            │
     └──────────────────────┬────────────────────────────┘
                            │
              ┌─────────────┼─────────────┐
              │             │             │
     ┌────────▼──────┐ ┌───▼────┐ ┌─────▼───────┐
     │  Notification │ │ Fraud  │ │  Settlement │
     │   Service     │ │ Engine │ │   Service   │
     └───────────────┘ └────────┘ └─────────────┘
```

---

## 📝 Functional Requirements

### FR Table — What the System Must DO

| ID | Requirement | Description | Priority | Service Owner |
|----|-------------|-------------|----------|---------------|
| FR-01 | **Payment Lifecycle** | Support complete payment flow: Create Order → Authorize → Capture → Settle. Also support Void and Refund operations. | P0 — Critical | Payment Service |
| FR-02 | **Merchant Management** | Merchant registration, KYC verification, API key generation, webhook configuration, and business profile management. | P0 — Critical | Merchant Service |
| FR-03 | **Authentication & Authorization** | JWT-based auth for dashboard users, API key + HMAC for server-to-server calls. Role-based access control (Admin, Merchant, Support). | P0 — Critical | Identity Service |
| FR-04 | **Fraud Detection** | Rule-based fraud scoring: velocity checks, amount thresholds, geo-anomaly detection, device fingerprinting, and blocklist matching. | P1 — High | Fraud Engine |
| FR-05 | **Settlement Processing** | End-of-day batch settlement: aggregate captured payments per merchant, calculate fees, generate settlement reports, and initiate bank transfers. | P1 — High | Settlement Service |
| FR-06 | **Webhook Delivery** | Reliable event notifications to merchant endpoints with retry logic, signature verification, and delivery status tracking. | P1 — High | Notification Service |
| FR-07 | **Real-time Notifications** | Email/SMS notifications for payment events (success, failure, refund). Template-based, multi-channel delivery with preference management. | P2 — Medium | Notification Service |
| FR-08 | **Analytics & Reporting** | Real-time dashboard metrics: success rate, average latency, revenue, top merchants. Historical reports with CSV/PDF export. | P2 — Medium | Analytics Service |
| FR-09 | **Payment Routing** | Intelligent routing to multiple acquiring banks based on success rate, cost, card network, and issuer. Support failover to backup acquirer. | P1 — High | Payment Router |
| FR-10 | **Idempotent Operations** | Every payment operation must be idempotent. Duplicate requests (same idempotency key) must return the original response without re-processing. | P0 — Critical | All Services |

### Payment State Machine

Understanding the payment lifecycle is critical. Every payment moves through these states:

```
                    ┌─────────────┐
                    │   CREATED   │ ← Order created, awaiting payment
                    └──────┬──────┘
                           │ Customer submits payment
                           ▼
                    ┌─────────────┐
                    │ AUTHORIZING │ ← Sent to bank, waiting response
                    └──────┬──────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
              ▼            ▼            ▼
     ┌──────────────┐ ┌────────┐ ┌──────────┐
     │  AUTHORIZED  │ │ FAILED │ │ DECLINED │
     └──────┬───────┘ └────────┘ └──────────┘
            │
       ┌────┼────┐
       │         │
       ▼         ▼
┌──────────┐ ┌────────┐
│ CAPTURED │ │ VOIDED │ ← Merchant cancels before capture
└────┬─────┘ └────────┘
     │
     ├─────────────────┐
     │                 │
     ▼                 ▼
┌──────────┐    ┌────────────┐
│ SETTLED  │    │  REFUNDED  │ ← Full or partial refund
└──────────┘    └────────────┘
```

### Key Business Rules

| Rule | Description | Example |
|------|-------------|---------|
| Auth Expiry | Authorization expires after 7 days if not captured | Hotel pre-auth for room deposit |
| Partial Capture | Merchant can capture less than authorized amount | Auth ₹1000, capture ₹800 |
| Partial Refund | Multiple refunds allowed up to captured amount | Refund ₹200, then ₹300 on ₹800 capture |
| Void Window | Void only allowed before settlement batch runs | Same-day void only |
| Idempotency TTL | Idempotency keys expire after 24 hours | Retry window for failed requests |
| Settlement Cut-off | Daily settlement at 23:59 IST | All captures before cut-off |

---

## ⚡ Non-Functional Requirements

### NFR Table — How Well the System Must Perform

| ID | Category | Requirement | Target | Measurement |
|----|----------|-------------|--------|-------------|
| NFR-01 | **Latency** | End-to-end payment API response time | p50 < 200ms, p95 < 500ms, p99 < 1000ms | Prometheus histogram |
| NFR-02 | **Availability** | System uptime | 99.9% (8.76 hours downtime/year) | Uptime monitoring |
| NFR-03 | **Throughput** | Transactions per second at peak | 1,000 TPS sustained, 2,000 TPS burst | Load testing (Gatling) |
| NFR-04 | **Durability** | Zero data loss for payment transactions | RPO = 0 (no data loss) | WAL + replication |
| NFR-05 | **Scalability** | Horizontal scaling capability | 10x growth without re-architecture | K8s HPA + partitioning |
| NFR-06 | **Security** | PCI-DSS Level 1 compliance | Full compliance | Annual audit |
| NFR-07 | **Consistency** | No double-charging or lost payments | Strong consistency for payments | Idempotency + DB constraints |
| NFR-08 | **Observability** | Full request tracing across services | 100% trace sampling | Jaeger/Zipkin |
| NFR-09 | **Recovery** | Recovery time after failure | RTO < 5 minutes | Chaos engineering tests |
| NFR-10 | **Maintainability** | Independent service deployment | Zero-downtime deploys | Blue-green deployment |

### Availability Math — What 99.9% Really Means

```
┌─────────────────────────────────────────────────────────────────┐
│ AVAILABILITY BUDGET CALCULATION                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  99.9% uptime = 0.1% downtime allowed                            │
│                                                                   │
│  Per Year:  365.25 × 24 × 60 × 0.001 = 525.96 minutes           │
│             = 8.76 hours/year                                     │
│                                                                   │
│  Per Month: 30 × 24 × 60 × 0.001 = 43.2 minutes                 │
│                                                                   │
│  Per Week:  7 × 24 × 60 × 0.001 = 10.08 minutes                 │
│                                                                   │
│  ⚠️  This includes EVERYTHING:                                   │
│      - Planned maintenance                                        │
│      - Deployments                                                │
│      - Infrastructure failures                                    │
│      - Dependency outages                                         │
│                                                                   │
│  💡 At 1000 TPS, 1 minute of downtime = 60,000 failed txns      │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

### Latency Breakdown Target

```
Client ──→ API Gateway ──→ Service ──→ Database ──→ Bank ──→ Response

  Network:    20ms      10ms      5ms       150ms     20ms
  Processing: --        30ms      50ms      --        --
  ─────────────────────────────────────────────────────────────
  Total Budget:                                       285ms (p50)
  Remaining Buffer:                                   215ms
```

---

## 🧠 Design Decisions

This section documents every major architectural decision with full reasoning.

### Decision 1: Architecture Style

```
┌─────────────────────────────────────────────────────────────────┐
│ 💡 DESIGN DECISION: Architecture Style — Microservices           │
├─────────────────────────────────────────────────────────────────┤
│ CHOSEN: Microservices Architecture (Domain-Driven)               │
│ WHY: Payment systems have clearly bounded contexts (payments,    │
│      merchants, identity, settlement) with different scaling     │
│      needs. Payment service needs 10x more instances than        │
│      settlement service.                                         │
│ ALTERNATIVES:                                                    │
│   ├── Monolith — Rejected: Cannot scale payment processing      │
│   │   independently. A bug in settlement could crash payments.   │
│   └── Serverless (Lambda) — Rejected: Cold starts add 200-800ms │
│       latency. Payment processing needs persistent connections   │
│       to banks. Connection pooling impossible with serverless.   │
│ TRADE-OFFS:                                                      │
│   ✅ Independent scaling, deployment, and failure isolation      │
│   ✅ Team autonomy — different teams own different services      │
│   ✅ Technology flexibility per service                          │
│   ❌ Network latency between services (mitigated by co-location)│
│   ❌ Distributed transactions complexity                         │
│   ❌ Operational overhead (more services to monitor)             │
│ AT SCALE: At 1000x, individual services can be geo-distributed. │
│   Payment service in multiple regions, settlement centralized.   │
│ REAL-WORLD: Stripe uses ~500 microservices. Razorpay started     │
│   monolith, migrated to microservices at scale. Square uses      │
│   service-oriented architecture with ~200 services.              │
│ 🎤 INTERVIEW TIP: "We chose microservices because payment       │
│    processing and settlement have fundamentally different         │
│    scaling characteristics and failure modes."                    │
└─────────────────────────────────────────────────────────────────┘
```

### Decision 2: Primary Database

```
┌─────────────────────────────────────────────────────────────────┐
│ 💡 DESIGN DECISION: Primary Database — PostgreSQL                │
├─────────────────────────────────────────────────────────────────┤
│ CHOSEN: PostgreSQL 15+ with JSONB support                        │
│ WHY: Financial data requires ACID transactions. PostgreSQL       │
│      offers the best combination of reliability, performance,    │
│      and features (JSONB, partitioning, row-level security).     │
│ ALTERNATIVES:                                                    │
│   ├── MySQL — Rejected: Weaker JSON support, no partial indexes,│
│   │   less sophisticated query planner. PostgreSQL's MVCC is     │
│   │   better suited for high-concurrency payment workloads.      │
│   └── MongoDB — Rejected: Eventual consistency by default is     │
│       unacceptable for financial transactions. No true ACID      │
│       across collections. "Lost write" scenarios possible.       │
│ TRADE-OFFS:                                                      │
│   ✅ ACID transactions — money never gets lost                   │
│   ✅ JSONB for flexible metadata without schema migrations       │
│   ✅ Table partitioning for time-series payment data             │
│   ✅ Excellent ecosystem (pg_stat, pgBouncer, logical replication│
│   ❌ Vertical scaling limits (solved by sharding/Citus)          │
│   ❌ More complex HA setup vs managed MongoDB Atlas              │
│ AT SCALE: At 1000x, use Citus for horizontal sharding by        │
│   merchant_id. Read replicas for analytics queries.              │
│ REAL-WORLD: Stripe uses PostgreSQL. Razorpay uses PostgreSQL +   │
│   MySQL. Square uses PostgreSQL for payment data.                │
│ 🎤 INTERVIEW TIP: "For financial systems, we chose PostgreSQL   │
│    because ACID compliance is non-negotiable when handling money. │
│    Eventual consistency means potential double-charging."         │
└─────────────────────────────────────────────────────────────────┘
```

### Decision 3: Database Strategy

```
┌─────────────────────────────────────────────────────────────────┐
│ 💡 DESIGN DECISION: Database Strategy — Database per Service     │
├─────────────────────────────────────────────────────────────────┤
│ CHOSEN: Dedicated database per microservice                      │
│ WHY: Loose coupling between services. Each service owns its      │
│      data schema and can evolve independently. Prevents          │
│      cross-service joins that create tight coupling.             │
│ ALTERNATIVES:                                                    │
│   ├── Shared Database — Rejected: Creates coupling between       │
│   │   services. Schema changes in one service can break others.  │
│   │   Impossible to scale databases independently.               │
│   └── Schema-per-Service (same instance) — Rejected: Still      │
│       shares connection pool and I/O bandwidth. A heavy          │
│       analytics query could starve payment writes.               │
│ TRADE-OFFS:                                                      │
│   ✅ Complete data isolation and ownership                       │
│   ✅ Independent scaling (payment DB can have more resources)    │
│   ✅ Independent technology choice per service                   │
│   ✅ Failure isolation (merchant DB down ≠ payment DB down)      │
│   ❌ No cross-service joins (use API composition or events)      │
│   ❌ Distributed transaction complexity (use Saga pattern)       │
│   ❌ More infrastructure to manage                               │
│ AT SCALE: Each database can be independently sharded, replicated,│
│   and geo-distributed based on its specific access patterns.     │
│ REAL-WORLD: Stripe has separate databases per domain. Amazon     │
│   mandated database-per-service in 2002 (the famous Bezos API    │
│   mandate). Netflix uses database-per-service extensively.        │
│ 🎤 INTERVIEW TIP: "Database-per-service ensures that a schema   │
│    migration in the merchant service can never break the          │
│    payment processing pipeline."                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Decision 4: Primary Key Strategy

```
┌─────────────────────────────────────────────────────────────────┐
│ 💡 DESIGN DECISION: Primary Key Strategy — UUIDs (v7)            │
├─────────────────────────────────────────────────────────────────┤
│ CHOSEN: UUID v7 (time-ordered) for all primary keys             │
│ WHY: UUID v7 combines the benefits of UUIDs (globally unique,    │
│      no coordination needed) with time-ordering (better B-tree   │
│      performance than random UUIDs). Essential for distributed   │
│      systems where multiple nodes create records simultaneously. │
│ ALTERNATIVES:                                                    │
│   ├── Auto-increment (BIGSERIAL) — Rejected: Reveals record     │
│   │   count to competitors. Requires coordination in distributed │
│   │   writes. Merging databases becomes nightmare.               │
│   └── UUID v4 (random) — Rejected: Random distribution causes   │
│       B-tree page splits and poor cache locality. Write          │
│       amplification increases 2-3x vs sequential keys.           │
│ TRADE-OFFS:                                                      │
│   ✅ Globally unique — no coordination between services          │
│   ✅ Time-ordered — excellent B-tree insert performance          │
│   ✅ Secure — doesn't reveal business information                │
│   ✅ Mergeable — databases can be combined without conflicts     │
│   ❌ 16 bytes vs 8 bytes (BIGINT) — more storage and index size │
│   ❌ Harder to debug (copy-paste long strings)                   │
│   ❌ Slightly slower joins than integer keys                     │
│ AT SCALE: UUID v7 supports multi-region writes without global    │
│   sequence coordination. Critical for geo-distributed systems.   │
│ REAL-WORLD: Stripe uses prefixed IDs (pay_xxxxx) built on UUIDs.│
│   Razorpay uses custom ID generation. Shopify uses UUID v4 with  │
│   optimization.                                                   │
│ 🎤 INTERVIEW TIP: "UUID v7 gives us global uniqueness for       │
│    distributed writes while maintaining B-tree insert efficiency  │
│    through time-ordering — best of both worlds."                  │
└─────────────────────────────────────────────────────────────────┘
```

### Decision 5: Message Broker

```
┌─────────────────────────────────────────────────────────────────┐
│ 💡 DESIGN DECISION: Message Broker — Apache Kafka                │
├─────────────────────────────────────────────────────────────────┤
│ CHOSEN: Apache Kafka 3.x with KRaft (no ZooKeeper)              │
│ WHY: Payment events must be durable, ordered (per-partition),    │
│      and replayable. Kafka's log-based architecture guarantees   │
│      at-least-once delivery and supports event sourcing.         │
│ ALTERNATIVES:                                                    │
│   ├── RabbitMQ — Rejected: Message deletion after consumption    │
│   │   prevents replay. No built-in partitioning for ordering.    │
│   │   Lower throughput at scale (50K vs 1M+ msgs/sec).           │
│   └── AWS SQS/SNS — Rejected: No ordering guarantees (standard  │
│       queues). FIFO queues limited to 300 msg/sec. Vendor        │
│       lock-in. No replay capability.                             │
│ TRADE-OFFS:                                                      │
│   ✅ Durable event log — replay events for debugging/recovery    │
│   ✅ Ordered per partition — crucial for payment state machines  │
│   ✅ High throughput — 1M+ messages/sec per cluster              │
│   ✅ Consumer groups — multiple services process same events     │
│   ❌ Operational complexity (topic management, partition sizing) │
│   ❌ At-least-once delivery requires idempotent consumers        │
│   ❌ Higher latency than RabbitMQ for single messages            │
│ AT SCALE: Kafka scales horizontally by adding brokers and        │
│   partitions. At 1000x, multi-cluster with MirrorMaker 2 for   │
│   geo-replication.                                               │
│ REAL-WORLD: Stripe uses Kafka for event processing. Razorpay    │
│   uses Kafka for async workflows. Square uses Kafka for payment  │
│   event streaming.                                                │
│ 🎤 INTERVIEW TIP: "Kafka's replayability is critical for        │
│    payment systems — if a consumer crashes, we can replay the    │
│    event log to recover exact state without data loss."           │
└─────────────────────────────────────────────────────────────────┘
```

### Decision 6: Caching Layer

```
┌─────────────────────────────────────────────────────────────────┐
│ 💡 DESIGN DECISION: Caching Layer — Redis                        │
├─────────────────────────────────────────────────────────────────┤
│ CHOSEN: Redis 7.x (Cluster mode for HA)                          │
│ WHY: Sub-millisecond reads for frequently accessed data:         │
│      merchant configs, rate limit counters, idempotency keys,    │
│      session tokens. Redis's data structures (sorted sets,       │
│      HyperLogLog) enable complex operations atomically.          │
│ ALTERNATIVES:                                                    │
│   ├── Memcached — Rejected: No persistence, no data structures  │
│   │   beyond key-value. Cannot implement rate limiting or        │
│   │   distributed locks natively.                                │
│   └── Hazelcast — Rejected: JVM-only client optimization.       │
│       Higher memory overhead. Less community adoption. Redis     │
│       has better tooling and monitoring.                          │
│ TRADE-OFFS:                                                      │
│   ✅ Sub-millisecond latency (~0.1ms for GET)                    │
│   ✅ Rich data structures (sets, sorted sets, streams)           │
│   ✅ Atomic operations (INCR for rate limiting, SETNX for locks)│
│   ✅ Built-in TTL for automatic cache expiry                     │
│   ❌ Memory-bound (expensive at scale)                           │
│   ❌ Single-threaded for writes (mitigated by cluster sharding)  │
│   ❌ Cache invalidation complexity                               │
│ AT SCALE: Redis Cluster with 6+ nodes (3 primary, 3 replica).  │
│   Separate clusters for caching vs rate limiting (different      │
│   eviction policies).                                            │
│ REAL-WORLD: Stripe uses Redis for rate limiting and caching.     │
│   Razorpay uses Redis for session management and idempotency.    │
│   PayPal uses Redis Cluster for high-throughput caching.         │
│ 🎤 INTERVIEW TIP: "Redis serves dual purpose: sub-millisecond   │
│    cache for merchant configs AND atomic counters for            │
│    distributed rate limiting — both critical for payment         │
│    gateway performance."                                          │
└─────────────────────────────────────────────────────────────────┘
```

### Decision 7: Authentication — JWT

```
┌─────────────────────────────────────────────────────────────────┐
│ 💡 DESIGN DECISION: Authentication — JWT with Short Expiry       │
├─────────────────────────────────────────────────────────────────┤
│ CHOSEN: JWT (RS256) with 15-minute access tokens + 7-day        │
│         refresh tokens stored in Redis                            │
│ WHY: JWTs are stateless — API Gateway can validate tokens        │
│      without hitting a database on every request. RS256          │
│      (asymmetric) allows any service to verify without knowing   │
│      the signing key.                                            │
│ ALTERNATIVES:                                                    │
│   ├── Opaque Tokens (session-based) — Rejected: Every request   │
│   │   requires a database/cache lookup. At 1000 TPS, that's     │
│   │   1000 additional cache hits per second just for auth.       │
│   └── JWT with HMAC (HS256) — Rejected: Symmetric key must be   │
│       shared with all services. Key rotation is complex.         │
│       Compromised service can forge tokens for any user.         │
│ TRADE-OFFS:                                                      │
│   ✅ Stateless validation — no DB hit per request                │
│   ✅ Asymmetric keys — only Identity Service has private key     │
│   ✅ Self-contained claims — role, merchant_id embedded          │
│   ❌ Cannot revoke individual tokens (mitigated by short expiry) │
│   ❌ Token size larger than opaque tokens (~800 bytes vs 32)     │
│   ❌ Clock skew issues in distributed systems                    │
│ AT SCALE: JWT validation is CPU-bound (RSA verification).       │
│   At extreme scale, use EdDSA (Ed25519) which is 10x faster.    │
│ REAL-WORLD: Stripe uses API keys for server-to-server (no JWT). │
│   Razorpay uses JWT for dashboard + API keys for integration.    │
│   Auth0/Okta recommend JWT with short expiry + refresh tokens.   │
│ 🎤 INTERVIEW TIP: "Short-lived JWTs (15 min) with refresh       │
│    tokens give us stateless auth with practical revocation —     │
│    worst case, a compromised token works for only 15 minutes."   │
└─────────────────────────────────────────────────────────────────┘
```

### Decision 8: API Authentication for Merchants

```
┌─────────────────────────────────────────────────────────────────┐
│ 💡 DESIGN DECISION: Merchant API Auth — API Keys + HMAC          │
├─────────────────────────────────────────────────────────────────┤
│ CHOSEN: API Key (public identifier) + HMAC-SHA256 request        │
│         signing (using secret key)                                │
│ WHY: Server-to-server integration needs key-based auth (not      │
│      user login). HMAC signing prevents replay attacks and       │
│      ensures request integrity. Two keys (public + secret)       │
│      allow safe logging of public key without exposing secret.   │
│ ALTERNATIVES:                                                    │
│   ├── OAuth 2.0 Client Credentials — Rejected: Adds token       │
│   │   exchange overhead for every API call. Overkill for         │
│   │   simple server-to-server integration.                       │
│   └── Basic Auth (API key as password) — Rejected: No request   │
│       integrity verification. Susceptible to MITM tampering      │
│       even over TLS (compromised proxy). No replay protection.   │
│ TRADE-OFFS:                                                      │
│   ✅ Simple integration for merchants                            │
│   ✅ Request integrity — tampering detected via HMAC mismatch   │
│   ✅ Replay protection — timestamp in HMAC prevents reuse       │
│   ✅ Key rotation without downtime (support multiple active keys)│
│   ❌ SDK complexity — merchants must implement HMAC signing      │
│   ❌ Clock synchronization needed for timestamp validation       │
│   ❌ Debugging harder (can't just curl without signing)          │
│ AT SCALE: API key lookups cached in Redis. Hash API keys in DB   │
│   (like passwords) — if DB is compromised, keys are useless.     │
│ REAL-WORLD: Stripe uses Bearer token (simplified API key).       │
│   Razorpay uses key_id + key_secret with HMAC. AWS uses          │
│   HMAC-based Signature V4 for all API authentication.            │
│ 🎤 INTERVIEW TIP: "HMAC signing ensures that even if someone    │
│    intercepts the request, they can't modify the amount or       │
│    recipient — the signature would break."                        │
└─────────────────────────────────────────────────────────────────┘
```

### Decision 9: Bank Communication Protocol

```
┌─────────────────────────────────────────────────────────────────┐
│ 💡 DESIGN DECISION: Bank Protocol — ISO 8583                     │
├─────────────────────────────────────────────────────────────────┤
│ CHOSEN: ISO 8583 (1993 version) for bank communication           │
│ WHY: ISO 8583 is the international standard for financial        │
│      transaction messaging. Every acquiring bank and card        │
│      network (Visa, Mastercard) uses ISO 8583. Learning this     │
│      protocol is essential for anyone building payment systems.  │
│ ALTERNATIVES:                                                    │
│   ├── REST/JSON to banks — Rejected: Banks don't offer REST     │
│   │   APIs for core transaction processing. Some offer REST for  │
│   │   ancillary services, but authorization/capture uses 8583.   │
│   └── XML (ISO 20022) — Rejected: ISO 20022 is for inter-bank  │
│       settlement (SWIFT, NEFT) not for real-time card            │
│       transaction processing. Different use case entirely.       │
│ TRADE-OFFS:                                                      │
│   ✅ Industry standard — works with any acquiring bank           │
│   ✅ Compact binary format — efficient over network              │
│   ✅ Well-defined field structure for all payment operations     │
│   ❌ Complex to implement (bitmap parsing, BCD encoding)         │
│   ❌ Dated protocol design (1987 origin)                         │
│   ❌ Debugging binary messages is difficult                      │
│ AT SCALE: Connection pooling to banks is critical. Maintain      │
│   persistent TCP connections (no HTTP overhead). Scale by        │
│   adding more connections to acquirer.                            │
│ REAL-WORLD: Stripe abstracts 8583 behind their API — merchants  │
│   never see it. Razorpay communicates with banks via ISO 8583.   │
│   Visa's VisaNet processes 65,000 TPS using ISO 8583.           │
│ 🎤 INTERVIEW TIP: "ISO 8583 is to payment systems what HTTP     │
│    is to web — the universal protocol that every bank speaks."   │
└─────────────────────────────────────────────────────────────────┘
```

### Decision 10: Network Layer

```
┌─────────────────────────────────────────────────────────────────┐
│ 💡 DESIGN DECISION: Network Layer — Netty for Bank Connections   │
├─────────────────────────────────────────────────────────────────┤
│ CHOSEN: Netty 4.x for persistent TCP connections to banks        │
│ WHY: Bank communication requires persistent TCP connections      │
│      (not HTTP). Netty's event-driven, non-blocking I/O model   │
│      handles thousands of concurrent connections with minimal    │
│      threads. ISO 8583 messages are binary — Netty's ByteBuf    │
│      pipeline is perfect for custom protocol encoding/decoding. │
│ ALTERNATIVES:                                                    │
│   ├── Java NIO directly — Rejected: Too low-level. Buffer       │
│   │   management, connection lifecycle, and error handling       │
│   │   complexity is enormous. Netty solves all of this.          │
│   └── gRPC — Rejected: Banks don't speak gRPC/HTTP2. ISO 8583  │
│       runs over raw TCP sockets. gRPC's framing doesn't match   │
│       the ISO 8583 message format.                               │
│ TRADE-OFFS:                                                      │
│   ✅ Non-blocking I/O — handle 10K+ connections with few threads │
│   ✅ Custom codec pipeline — perfect for ISO 8583 encoding       │
│   ✅ Connection pooling built-in                                 │
│   ✅ Battle-tested at scale (Apple, Twitter, Facebook use Netty) │
│   ❌ Steeper learning curve than blocking I/O                    │
│   ❌ Callback-based programming can be complex                   │
│   ❌ Debugging async code is harder                              │
│ AT SCALE: Netty can handle 100K+ concurrent connections per JVM.│
│   At 1000x, add more connection pool instances behind a load    │
│   balancer for bank-side connections.                             │
│ REAL-WORLD: Stripe uses custom network layers for bank comms.    │
│   Adyen uses Netty for their payment infrastructure. Apple Push  │
│   Notification service is built on Netty.                        │
│ 🎤 INTERVIEW TIP: "Netty gives us non-blocking TCP connections  │
│    to banks, handling ISO 8583 binary encoding with custom       │
│    codecs — exactly what payment processing needs."              │
└─────────────────────────────────────────────────────────────────┘
```

### Decision 11: API Gateway

```
┌─────────────────────────────────────────────────────────────────┐
│ 💡 DESIGN DECISION: API Gateway — Spring Cloud Gateway           │
├─────────────────────────────────────────────────────────────────┤
│ CHOSEN: Spring Cloud Gateway (reactive, WebFlux-based)           │
│ WHY: Non-blocking request handling matches our high-throughput   │
│      needs. Native integration with Spring ecosystem (Security,  │
│      Actuator). Programmable filters for JWT validation,         │
│      rate limiting, request logging, and API key validation.     │
│ ALTERNATIVES:                                                    │
│   ├── Kong (Nginx + Lua) — Rejected: Separate technology stack  │
│   │   from backend services. Lua plugins harder to test and      │
│   │   debug. Less integration with Spring ecosystem.             │
│   └── AWS API Gateway — Rejected: Vendor lock-in. Cold starts   │
│       add latency. Limited customization for complex auth flows. │
│       Per-request pricing expensive at 1000 TPS.                 │
│ TRADE-OFFS:                                                      │
│   ✅ Same technology stack as backend (Java/Spring)              │
│   ✅ Reactive/non-blocking — high concurrency with low threads  │
│   ✅ Programmable filters — custom auth, rate limiting, logging  │
│   ✅ Built-in circuit breaker integration (Resilience4j)         │
│   ❌ JVM startup time (mitigated by GraalVM native image)        │
│   ❌ Less battle-tested than Nginx/Kong at extreme scale         │
│   ❌ WebFlux learning curve for team                             │
│ AT SCALE: Multiple gateway instances behind L4 load balancer.    │
│   At 1000x, consider Envoy sidecar pattern for service mesh.    │
│ REAL-WORLD: Many Spring-based fintech companies use Spring Cloud │
│   Gateway. PhonePe (India's largest UPI app) uses Spring         │
│   ecosystem. Alternatively, Stripe uses custom Nginx config.     │
│ 🎤 INTERVIEW TIP: "Spring Cloud Gateway's reactive model means  │
│    we handle 10K concurrent requests with just 4 threads —      │
│    critical when each request waits 150ms for bank response."    │
└─────────────────────────────────────────────────────────────────┘
```

### Decision 12: Idempotency Strategy

```
┌─────────────────────────────────────────────────────────────────┐
│ 💡 DESIGN DECISION: Idempotency — Client-Generated Keys          │
├─────────────────────────────────────────────────────────────────┤
│ CHOSEN: Client-generated idempotency keys (UUID) stored in       │
│         Redis with 24-hour TTL, backed by PostgreSQL             │
│ WHY: Payment APIs MUST be idempotent. Network failures cause     │
│      retries — without idempotency, a retry could charge a       │
│      customer twice. Client-generated keys give merchants full   │
│      control over deduplication.                                 │
│ ALTERNATIVES:                                                    │
│   ├── Server-generated (response contains key for retry) —      │
│   │   Rejected: Client doesn't have key for first request. Race │
│   │   conditions when first request times out but succeeds.      │
│   └── Natural key (amount + merchant + timestamp) — Rejected:   │
│       Legitimate duplicate charges would be blocked (same        │
│       customer buying same item twice in same second).           │
│ TRADE-OFFS:                                                      │
│   ✅ Merchant controls deduplication — clear contract            │
│   ✅ Works even if our response is lost (retry with same key)    │
│   ✅ Redis provides sub-ms lookup for hot path                   │
│   ✅ PostgreSQL backup ensures durability across Redis restarts  │
│   ❌ Merchants must generate and manage idempotency keys         │
│   ❌ 24-hour TTL means very late retries won't be caught         │
│   ❌ Storage overhead (one Redis entry per API call)             │
│ AT SCALE: Shard idempotency keys by merchant_id across Redis     │
│   cluster. Partition PostgreSQL backup table by date.            │
│ REAL-WORLD: Stripe requires Idempotency-Key header. Razorpay    │
│   uses receipt field for idempotency. PayPal uses request-id     │
│   header with similar semantics.                                  │
│ 🎤 INTERVIEW TIP: "Idempotency is the difference between a      │
│    payment gateway and a payment disaster. Our two-layer         │
│    approach (Redis hot + PostgreSQL cold) ensures we never       │
│    double-charge even across infrastructure failures."            │
└─────────────────────────────────────────────────────────────────┘
```

### Decision 13: Retry Strategy

```
┌─────────────────────────────────────────────────────────────────┐
│ 💡 DESIGN DECISION: Retry Strategy — Exponential Backoff + Jitter│
├─────────────────────────────────────────────────────────────────┤
│ CHOSEN: Exponential backoff with full jitter and maximum         │
│         retry cap (3 retries, max 30 seconds)                    │
│ WHY: Failed bank connections and transient errors need retries.  │
│      Exponential backoff prevents thundering herd when a bank    │
│      recovers. Jitter ensures retries don't synchronize across  │
│      thousands of concurrent requests.                           │
│ ALTERNATIVES:                                                    │
│   ├── Fixed interval retry — Rejected: All retries hit at same  │
│   │   time, creating thundering herd effect. Bank gets slammed   │
│   │   with synchronized retries and fails again.                 │
│   └── Linear backoff — Rejected: Still allows synchronization.  │
│       Exponential spreads retries much more effectively.         │
│       Amazon's research proves exponential + jitter is optimal.  │
│ TRADE-OFFS:                                                      │
│   ✅ Prevents thundering herd effect                             │
│   ✅ Jitter eliminates retry synchronization                     │
│   ✅ Gives transient failures time to recover                    │
│   ✅ Bounded — max 3 retries prevents infinite loops             │
│   ❌ Adds latency for retried requests                           │
│   ❌ Total retry time can be significant (1 + 2 + 4 = 7 sec)    │
│   ❌ Must be combined with idempotency (retry could double-do)   │
│ AT SCALE: Circuit breaker prevents retries when failure is       │
│   systemic (see Decision 14). Retries only for transient errors. │
│ REAL-WORLD: AWS recommends exponential backoff + jitter for all  │
│   SDK retries. Stripe retries with exponential backoff.          │
│   Google Cloud uses truncated exponential backoff.               │
│ 🎤 INTERVIEW TIP: "The formula is: delay = min(cap, base × 2^n)│
│    + random_between(0, delay). The jitter is what prevents       │
│    thousands of retries from hitting the bank simultaneously."   │
└─────────────────────────────────────────────────────────────────┘
```

### Decision 14: Resilience Pattern

```
┌─────────────────────────────────────────────────────────────────┐
│ 💡 DESIGN DECISION: Resilience — Circuit Breaker (Resilience4j)  │
├─────────────────────────────────────────────────────────────────┤
│ CHOSEN: Resilience4j Circuit Breaker with sliding window         │
│ WHY: When a bank endpoint is down, we shouldn't keep sending     │
│      requests (wasting time and resources). Circuit breaker      │
│      fails fast after detecting sustained failures, allowing     │
│      the system to route to backup acquirers.                    │
│ ALTERNATIVES:                                                    │
│   ├── Netflix Hystrix — Rejected: Deprecated and in maintenance │
│   │   mode. Resilience4j is the recommended successor with       │
│   │   better performance and functional API.                     │
│   └── Custom implementation — Rejected: Getting circuit breaker │
│       right is complex (state machine, half-open probes,         │
│       concurrent access). Use proven library.                    │
│ TRADE-OFFS:                                                      │
│   ✅ Fast failure — don't waste 30 seconds waiting for timeout   │
│   ✅ Automatic recovery — half-open state probes for recovery    │
│   ✅ Metrics integration — dashboard shows circuit state         │
│   ✅ Triggers failover to backup acquirer                        │
│   ❌ Adds complexity to request flow                             │
│   ❌ Tuning thresholds requires production traffic data          │
│   ❌ Half-open probes may route customer payments to failing bank│
│ AT SCALE: Per-acquirer circuit breakers. Bank A down? Route to   │
│   Bank B. All banks down? Return graceful degradation response.  │
│ REAL-WORLD: Stripe has per-bank circuit breakers with automatic  │
│   failover. Razorpay uses circuit breakers for bank APIs.        │
│   Netflix popularized the circuit breaker pattern.               │
│ 🎤 INTERVIEW TIP: "Circuit breakers in payment systems enable   │
│    intelligent routing — when Bank A's failure rate exceeds 50%,│
│    we automatically route to Bank B within milliseconds."         │
└─────────────────────────────────────────────────────────────────┘
```

### Decision 15: Settlement Strategy

```
┌─────────────────────────────────────────────────────────────────┐
│ 💡 DESIGN DECISION: Settlement — Batch Processing                │
├─────────────────────────────────────────────────────────────────┤
│ CHOSEN: End-of-day batch settlement with reconciliation          │
│ WHY: Real-time settlement is not how banks work. Acquiring       │
│      banks settle in batches (T+1 or T+2). Our settlement       │
│      service aggregates captured transactions per merchant,      │
│      calculates fees, and submits batch files to banks.          │
│ ALTERNATIVES:                                                    │
│   ├── Real-time settlement — Rejected: Banks don't support it   │
│   │   for card transactions. Only UPI supports near-real-time    │
│   │   settlement. Card networks batch by design.                 │
│   └── Per-transaction settlement — Rejected: Extremely high     │
│       bank fees for individual settlements. Batching reduces     │
│       per-transaction cost by 90%.                               │
│ TRADE-OFFS:                                                      │
│   ✅ Matches banking industry standard (T+1/T+2)                │
│   ✅ Lower per-transaction costs through batching                │
│   ✅ Reconciliation catches discrepancies before payout          │
│   ✅ Allows manual review of large/suspicious settlements        │
│   ❌ Merchants receive funds next day (not instant)              │
│   ❌ Batch failures affect all merchants in that batch           │
│   ❌ Complex reconciliation logic (our records vs bank records)  │
│ AT SCALE: Partition settlement batches by merchant tier.          │
│   Large merchants get dedicated batches. Parallel batch          │
│   processing with idempotent settlement records.                 │
│ REAL-WORLD: Stripe settles T+2 (2 business days). Razorpay      │
│   settles T+2 standard, T+1 for premium merchants.              │
│   Visa/Mastercard networks settle in batch cycles.               │
│ 🎤 INTERVIEW TIP: "Settlement is inherently batch because       │
│    that's how the banking system works. We optimize by           │
│    parallelizing merchant-level batches and providing instant    │
│    settlement as a premium feature (we absorb the risk)."        │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🌐 API Design

### Design Principles

Before listing endpoints, let's establish our API design principles:

| Principle | Implementation | Example |
|-----------|---------------|---------|
| RESTful | Resources as nouns, HTTP verbs for actions | `POST /payments` not `POST /createPayment` |
| Versioned | URL path versioning | `/v1/payments` |
| Consistent | Standard response envelope | `{ "success": true, "data": {...}, "error": null }` |
| Idempotent | Idempotency-Key header for POST/PATCH | Header: `Idempotency-Key: uuid` |
| Paginated | Cursor-based pagination | `?cursor=abc&limit=20` |
| Filterable | Query parameters for filtering | `?status=CAPTURED&from=2024-01-01` |

### Standard Response Envelope

Every API response follows this structure:

```json
{
  "success": true,
  "data": {
    "id": "pay_a1b2c3d4e5f6",
    "amount": 50000,
    "currency": "INR",
    "status": "AUTHORIZED"
  },
  "error": null,
  "metadata": {
    "request_id": "req_xyz789",
    "timestamp": "2024-01-15T10:30:00Z",
    "version": "v1"
  }
}
```

Error response:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_AMOUNT",
    "message": "Amount must be greater than 100 (₹1.00)",
    "field": "amount",
    "doc_url": "https://docs.payflow.dev/errors#INVALID_AMOUNT"
  },
  "metadata": {
    "request_id": "req_xyz790",
    "timestamp": "2024-01-15T10:30:01Z",
    "version": "v1"
  }
}
```

### Standard HTTP Status Codes

| Code | Meaning | When Used |
|------|---------|-----------|
| 200 | OK | Successful GET, PUT, PATCH |
| 201 | Created | Successful POST that creates resource |
| 202 | Accepted | Async operation accepted (settlement trigger) |
| 400 | Bad Request | Validation error, malformed JSON |
| 401 | Unauthorized | Missing/invalid authentication |
| 403 | Forbidden | Valid auth but insufficient permissions |
| 404 | Not Found | Resource doesn't exist |
| 409 | Conflict | Idempotency key reuse with different params |
| 422 | Unprocessable Entity | Valid JSON but business rule violation |
| 429 | Too Many Requests | Rate limit exceeded |
| 500 | Internal Server Error | Unexpected server failure |
| 502 | Bad Gateway | Bank/upstream service unavailable |
| 503 | Service Unavailable | System overloaded, try later |

---

### 🔐 Identity Service API

**Base URL:** `/v1/identity`
**Auth:** Public (register/login) or JWT (profile/refresh)

| # | Method | Endpoint | Description | Auth | Request Body | Response |
|---|--------|----------|-------------|------|--------------|----------|
| 1 | POST | `/register` | Register new user | Public | `{ "email", "password", "name", "role" }` | `201: { "user_id", "email", "role" }` |
| 2 | POST | `/login` | Authenticate user | Public | `{ "email", "password" }` | `200: { "access_token", "refresh_token", "expires_in" }` |
| 3 | POST | `/refresh` | Refresh access token | Refresh Token | `{ "refresh_token" }` | `200: { "access_token", "refresh_token", "expires_in" }` |
| 4 | POST | `/logout` | Invalidate tokens | JWT | `{ "refresh_token" }` | `200: { "message": "Logged out" }` |
| 5 | GET | `/profile` | Get current user | JWT | — | `200: { "user_id", "email", "name", "role", "created_at" }` |
| 6 | PUT | `/profile` | Update user profile | JWT | `{ "name", "phone" }` | `200: { "user_id", "email", "name", "phone" }` |
| 7 | POST | `/password/change` | Change password | JWT | `{ "current_password", "new_password" }` | `200: { "message": "Password changed" }` |
| 8 | POST | `/password/reset` | Request password reset | Public | `{ "email" }` | `200: { "message": "Reset email sent" }` |
| 9 | POST | `/password/reset/confirm` | Confirm reset | Public | `{ "token", "new_password" }` | `200: { "message": "Password reset successful" }` |
| 10 | GET | `/users` | List users (Admin) | JWT (Admin) | — | `200: { "users": [...], "cursor", "total" }` |

**Register Request Example:**

```http
POST /v1/identity/register
Content-Type: application/json

{
  "email": "merchant@example.com",
  "password": "SecureP@ss123!",
  "name": "Acme Corp",
  "role": "MERCHANT"
}
```

**Login Response Example:**

```json
{
  "success": true,
  "data": {
    "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refresh_token": "dGhpcyBpcyBhIHJlZnJlc2ggdG9rZW4...",
    "token_type": "Bearer",
    "expires_in": 900
  }
}
```

---

### 🏪 Merchant Service API

**Base URL:** `/v1/merchants`
**Auth:** JWT (Dashboard) or API Key (Server-to-Server)

| # | Method | Endpoint | Description | Auth | Request Body | Response |
|---|--------|----------|-------------|------|--------------|----------|
| 1 | POST | `/` | Register merchant | JWT | `{ "business_name", "business_type", "gstin", "pan", "bank_account" }` | `201: { "merchant_id", "status": "PENDING_KYC" }` |
| 2 | GET | `/me` | Get my merchant profile | JWT | — | `200: { "merchant_id", "business_name", "status", "config" }` |
| 3 | PUT | `/me` | Update merchant profile | JWT | `{ "business_name", "website", "support_email" }` | `200: { "merchant_id", "updated_fields" }` |
| 4 | GET | `/:id` | Get merchant by ID (Admin) | JWT (Admin) | — | `200: { "merchant_id", "business_name", "status", ... }` |
| 5 | PATCH | `/:id/status` | Update KYC status (Admin) | JWT (Admin) | `{ "status": "ACTIVE" \| "SUSPENDED" }` | `200: { "merchant_id", "status" }` |
| 6 | POST | `/me/api-keys` | Generate new API key pair | JWT | `{ "label", "environment" }` | `201: { "key_id", "key_secret", "label" }` |
| 7 | GET | `/me/api-keys` | List API keys | JWT | — | `200: { "keys": [{ "key_id", "label", "created_at", "last_used" }] }` |
| 8 | DELETE | `/me/api-keys/:keyId` | Revoke API key | JWT | — | `200: { "message": "Key revoked" }` |
| 9 | POST | `/me/webhooks` | Register webhook URL | JWT | `{ "url", "events": ["payment.captured", ...] }` | `201: { "webhook_id", "url", "events" }` |
| 10 | GET | `/me/webhooks` | List webhooks | JWT | — | `200: { "webhooks": [...] }` |
| 11 | PUT | `/me/webhooks/:id` | Update webhook | JWT | `{ "url", "events", "active" }` | `200: { "webhook_id", "url", "events" }` |
| 12 | DELETE | `/me/webhooks/:id` | Delete webhook | JWT | — | `200: { "message": "Webhook deleted" }` |
| 13 | GET | `/me/config` | Get merchant config | JWT | — | `200: { "auto_capture", "settlement_schedule", "retry_config" }` |
| 14 | PUT | `/me/config` | Update merchant config | JWT | `{ "auto_capture": true, "webhook_secret_rotation": false }` | `200: { "config" }` |

**Generate API Key Response:**

```json
{
  "success": true,
  "data": {
    "key_id": "key_live_a1b2c3d4e5",
    "key_secret": "sk_live_9f8e7d6c5b4a3210...",
    "label": "Production Backend",
    "environment": "live",
    "created_at": "2024-01-15T10:30:00Z"
  },
  "warning": "Store the key_secret securely. It won't be shown again."
}
```

---

### 💳 Payment Service API

**Base URL:** `/v1/payments`
**Auth:** API Key + HMAC Signature (Server-to-Server)

| # | Method | Endpoint | Description | Auth | Idempotent | Request Body | Response |
|---|--------|----------|-------------|------|------------|--------------|----------|
| 1 | POST | `/orders` | Create payment order | API Key | Yes | `{ "amount", "currency", "receipt", "notes" }` | `201: { "order_id", "amount", "status": "CREATED" }` |
| 2 | GET | `/orders/:id` | Get order details | API Key | N/A | — | `200: { "order_id", "amount", "status", "payments" }` |
| 3 | GET | `/orders` | List orders | API Key | N/A | Query params | `200: { "orders": [...], "cursor" }` |
| 4 | POST | `/orders/:id/authorize` | Authorize payment | API Key | Yes | `{ "payment_method", "card_details" \| "upi_id" }` | `200: { "payment_id", "status": "AUTHORIZED" }` |
| 5 | POST | `/payments/:id/capture` | Capture authorized payment | API Key | Yes | `{ "amount" }` (optional, for partial) | `200: { "payment_id", "status": "CAPTURED", "captured_amount" }` |
| 6 | POST | `/payments/:id/void` | Void authorization | API Key | Yes | `{ "reason" }` | `200: { "payment_id", "status": "VOIDED" }` |
| 7 | POST | `/payments/:id/refund` | Refund captured payment | API Key | Yes | `{ "amount", "reason", "notes" }` | `200: { "refund_id", "amount", "status": "PROCESSING" }` |
| 8 | GET | `/payments/:id` | Get payment details | API Key | N/A | — | `200: { "payment_id", "order_id", "amount", "status", "timeline" }` |
| 9 | GET | `/payments` | List payments | API Key | N/A | Query params | `200: { "payments": [...], "cursor" }` |
| 10 | GET | `/payments/:id/refunds` | List refunds for payment | API Key | N/A | — | `200: { "refunds": [...] }` |
| 11 | GET | `/refunds/:id` | Get refund details | API Key | N/A | — | `200: { "refund_id", "payment_id", "amount", "status" }` |

**Create Order Request:**

```http
POST /v1/payments/orders
Content-Type: application/json
X-Api-Key: key_live_a1b2c3d4e5
X-Timestamp: 2024-01-15T10:30:00Z
X-Signature: HMAC-SHA256(body + timestamp, secret)
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000

{
  "amount": 50000,
  "currency": "INR",
  "receipt": "order_receipt_123",
  "notes": {
    "customer_name": "Rahul Kumar",
    "product": "Premium Plan"
  }
}
```

**Authorize Payment Request:**

```http
POST /v1/payments/orders/ord_a1b2c3d4/authorize
Content-Type: application/json
X-Api-Key: key_live_a1b2c3d4e5
X-Timestamp: 2024-01-15T10:30:05Z
X-Signature: HMAC-SHA256(body + timestamp, secret)
Idempotency-Key: 660e8400-e29b-41d4-a716-446655440001

{
  "payment_method": "card",
  "card": {
    "number": "4111111111111111",
    "expiry_month": 12,
    "expiry_year": 2025,
    "cvv": "123",
    "holder_name": "RAHUL KUMAR"
  }
}
```

**Payment Response with Timeline:**

```json
{
  "success": true,
  "data": {
    "payment_id": "pay_x1y2z3w4",
    "order_id": "ord_a1b2c3d4",
    "amount": 50000,
    "currency": "INR",
    "status": "CAPTURED",
    "method": "card",
    "card": {
      "last4": "1111",
      "network": "visa",
      "issuer": "HDFC Bank"
    },
    "timeline": [
      { "status": "CREATED", "at": "2024-01-15T10:30:00Z" },
      { "status": "AUTHORIZED", "at": "2024-01-15T10:30:05Z" },
      { "status": "CAPTURED", "at": "2024-01-15T10:30:06Z" }
    ],
    "created_at": "2024-01-15T10:30:00Z",
    "captured_at": "2024-01-15T10:30:06Z"
  }
}
```

---

### 🏦 Settlement Service API

**Base URL:** `/v1/settlements`
**Auth:** JWT (Admin/Internal)

| # | Method | Endpoint | Description | Auth | Request Body | Response |
|---|--------|----------|-------------|------|--------------|----------|
| 1 | POST | `/trigger` | Trigger settlement batch | JWT (Admin) | `{ "date", "merchant_id" (optional) }` | `202: { "batch_id", "status": "PROCESSING" }` |
| 2 | GET | `/` | List settlement batches | JWT | Query params | `200: { "settlements": [...], "cursor" }` |
| 3 | GET | `/:batchId` | Get batch details | JWT | — | `200: { "batch_id", "status", "total_amount", "merchant_count" }` |
| 4 | GET | `/:batchId/merchants` | Get merchant settlements in batch | JWT | — | `200: { "merchant_settlements": [...] }` |
| 5 | GET | `/merchants/:merchantId` | Get merchant settlement history | JWT | Query params | `200: { "settlements": [...], "total_settled" }` |
| 6 | POST | `/:batchId/retry` | Retry failed settlement | JWT (Admin) | — | `202: { "batch_id", "status": "RETRYING" }` |
| 7 | GET | `/reports/:date` | Get settlement report | JWT | — | `200: { "date", "summary", "per_merchant": [...] }` |
| 8 | GET | `/reconciliation/:date` | Get reconciliation status | JWT (Admin) | — | `200: { "matched", "mismatched", "pending" }` |

---

### 🔀 Payment Router API (Internal)

**Base URL:** `/internal/v1/routing`
**Auth:** Internal Service Token (mTLS)

| # | Method | Endpoint | Description | Auth | Request Body | Response |
|---|--------|----------|-------------|------|--------------|----------|
| 1 | POST | `/route` | Get optimal route for payment | Internal | `{ "amount", "currency", "card_network", "issuer_bank" }` | `200: { "acquirer_id", "priority", "reason" }` |
| 2 | GET | `/acquirers` | List acquirer health | Internal | — | `200: { "acquirers": [{ "id", "health", "success_rate" }] }` |
| 3 | POST | `/acquirers/:id/health` | Report acquirer result | Internal | `{ "success": bool, "latency_ms" }` | `200: { "updated" }` |
| 4 | GET | `/rules` | Get routing rules | Internal | — | `200: { "rules": [...] }` |
| 5 | PUT | `/rules` | Update routing rules | Internal (Admin) | `{ "rules": [...] }` | `200: { "rules": [...] }` |

---

### 🔔 Webhook/Notification Service API (Internal)

**Base URL:** `/internal/v1/notifications`
**Auth:** Internal Service Token

| # | Method | Endpoint | Description | Auth | Request Body | Response |
|---|--------|----------|-------------|------|--------------|----------|
| 1 | POST | `/webhooks/deliver` | Queue webhook delivery | Internal | `{ "merchant_id", "event", "payload" }` | `202: { "delivery_id" }` |
| 2 | GET | `/webhooks/deliveries` | List delivery attempts | Internal | Query params | `200: { "deliveries": [...] }` |
| 3 | POST | `/email/send` | Send email notification | Internal | `{ "to", "template", "variables" }` | `202: { "email_id" }` |
| 4 | POST | `/sms/send` | Send SMS notification | Internal | `{ "to", "template", "variables" }` | `202: { "sms_id" }` |

---

## 🗄️ Database Design

### Database Strategy Overview

```
┌───────────────────────────────────────────────────────────────────┐
│                    DATABASE ARCHITECTURE                            │
├───────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐            │
│  │  identity_db │  │ merchant_db  │  │  payment_db  │            │
│  │              │  │              │  │              │            │
│  │  • users     │  │  • merchants │  │  • orders    │            │
│  │  • roles     │  │  • api_keys  │  │  • payments  │            │
│  │  • tokens    │  │  • webhooks  │  │  • refunds   │            │
│  │              │  │  • configs   │  │  • idempotency│           │
│  └──────────────┘  └──────────────┘  └──────┬───────┘            │
│                                              │                     │
│                                     ┌────────▼───────┐            │
│                                     │ settlement_db  │            │
│                                     │                │            │
│                                     │ • batches      │            │
│                                     │ • settlements  │            │
│                                     │ • reconciliation│           │
│                                     └────────────────┘            │
│                                                                     │
└───────────────────────────────────────────────────────────────────┘
```

### Naming Conventions

| Convention | Rule | Example |
|-----------|------|---------|
| Table names | Plural, snake_case | `payments`, `api_keys` |
| Column names | Singular, snake_case | `merchant_id`, `created_at` |
| Primary keys | `id` (UUID v7) | `id UUID PRIMARY KEY` |
| Foreign keys | `{table_singular}_id` | `merchant_id`, `order_id` |
| Timestamps | `{action}_at` | `created_at`, `captured_at` |
| Boolean | `is_{adjective}` | `is_active`, `is_deleted` |
| Indexes | `idx_{table}_{columns}` | `idx_payments_merchant_status` |
| Constraints | `chk_{table}_{rule}` | `chk_payments_amount_positive` |

---

### Identity Database — `identity_db`

```sql
-- ============================================================
-- DATABASE: identity_db
-- SERVICE:  Identity Service
-- PURPOSE:  User authentication, authorization, and session management
-- ============================================================

-- Users table: stores all system users (merchants, admins, support)
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,       -- bcrypt hash, cost factor 12
    name            VARCHAR(255) NOT NULL,
    phone           VARCHAR(20),
    role            VARCHAR(50) NOT NULL DEFAULT 'MERCHANT',
                    -- MERCHANT, ADMIN, SUPPORT, DEVELOPER
    status          VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
                    -- ACTIVE, SUSPENDED, LOCKED, PENDING_VERIFICATION
    email_verified  BOOLEAN NOT NULL DEFAULT FALSE,
    phone_verified  BOOLEAN NOT NULL DEFAULT FALSE,
    failed_attempts INTEGER NOT NULL DEFAULT 0,  -- for account locking
    locked_until    TIMESTAMP WITH TIME ZONE,    -- account lock expiry
    last_login_at   TIMESTAMP WITH TIME ZONE,
    last_login_ip   INET,                        -- PostgreSQL INET type
    metadata        JSONB DEFAULT '{}',          -- flexible additional data
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_role CHECK (role IN ('MERCHANT', 'ADMIN', 'SUPPORT', 'DEVELOPER')),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'LOCKED', 'PENDING_VERIFICATION'))
);

-- Index for login lookups (most frequent query)
CREATE INDEX idx_users_email ON users(email) WHERE status = 'ACTIVE';
-- Index for admin user listing
CREATE INDEX idx_users_role_status ON users(role, status);

COMMENT ON TABLE users IS 'All system users including merchants, admins, and support staff';
COMMENT ON COLUMN users.password_hash IS 'bcrypt hash with cost factor 12. Never store plain text.';
COMMENT ON COLUMN users.failed_attempts IS 'Incremented on failed login. Reset on successful login. Lock at 5.';


-- Refresh tokens: stored server-side for revocation capability
CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      VARCHAR(255) NOT NULL,       -- SHA-256 hash of actual token
    device_info     VARCHAR(500),                -- user agent / device fingerprint
    ip_address      INET,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    is_revoked      BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at      TIMESTAMP WITH TIME ZONE,
    revoked_reason  VARCHAR(255),               -- LOGOUT, SECURITY, ROTATION
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);

-- Index for token validation (lookup by hash)
CREATE INDEX idx_refresh_tokens_hash ON refresh_tokens(token_hash) 
    WHERE is_revoked = FALSE;
-- Index for cleanup of expired tokens
CREATE INDEX idx_refresh_tokens_expiry ON refresh_tokens(expires_at) 
    WHERE is_revoked = FALSE;
-- Index for revoking all user tokens (security event)
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id) 
    WHERE is_revoked = FALSE;

COMMENT ON TABLE refresh_tokens IS 'Server-side refresh token storage for revocation support';
COMMENT ON COLUMN refresh_tokens.token_hash IS 'SHA-256 hash. Actual token only sent to client once.';


-- Password reset tokens
CREATE TABLE password_reset_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      VARCHAR(255) NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at         TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_password_reset_hash UNIQUE (token_hash)
);

CREATE INDEX idx_password_reset_token ON password_reset_tokens(token_hash) 
    WHERE used_at IS NULL;

COMMENT ON TABLE password_reset_tokens IS 'One-time use tokens for password reset flow. Expire after 1 hour.';


-- Audit log for security-sensitive actions
CREATE TABLE identity_audit_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID REFERENCES users(id),
    action          VARCHAR(100) NOT NULL,       -- LOGIN, LOGOUT, PASSWORD_CHANGE, etc.
    ip_address      INET,
    user_agent      TEXT,
    details         JSONB DEFAULT '{}',          -- action-specific details
    success         BOOLEAN NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Partitioned by month for efficient cleanup
CREATE INDEX idx_audit_log_user_time ON identity_audit_log(user_id, created_at DESC);
CREATE INDEX idx_audit_log_action ON identity_audit_log(action, created_at DESC);

COMMENT ON TABLE identity_audit_log IS 'Immutable audit trail for all auth events. Retained for 2 years.';
```

---

### Merchant Database — `merchant_db`

```sql
-- ============================================================
-- DATABASE: merchant_db
-- SERVICE:  Merchant Service
-- PURPOSE:  Merchant profiles, API keys, webhooks, and configuration
-- ============================================================

-- Merchants: business entities that process payments
CREATE TABLE merchants (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL,              -- FK to identity_db.users (logical, not physical)
    business_name       VARCHAR(255) NOT NULL,
    display_name        VARCHAR(255),               -- shown on payment page
    business_type       VARCHAR(100) NOT NULL,      -- INDIVIDUAL, PARTNERSHIP, COMPANY, TRUST
    category_code       VARCHAR(10),                -- MCC (Merchant Category Code)
    website             VARCHAR(500),
    support_email       VARCHAR(255),
    support_phone       VARCHAR(20),
    logo_url            VARCHAR(500),
    
    -- KYC details
    gstin               VARCHAR(15),                -- GST Identification Number
    pan                 VARCHAR(10) NOT NULL,       -- Permanent Account Number
    cin                 VARCHAR(21),                -- Company Identification Number
    
    -- Bank account for settlement
    bank_account_name   VARCHAR(255),
    bank_account_number VARCHAR(50),               -- encrypted at rest
    bank_ifsc           VARCHAR(11),
    bank_name           VARCHAR(255),
    
    -- Status and verification
    status              VARCHAR(50) NOT NULL DEFAULT 'PENDING_KYC',
                        -- PENDING_KYC, KYC_SUBMITTED, ACTIVE, SUSPENDED, TERMINATED
    kyc_verified_at     TIMESTAMP WITH TIME ZONE,
    kyc_verified_by     UUID,                      -- admin user who verified
    
    -- Limits
    daily_limit         BIGINT DEFAULT 10000000,   -- in paise (₹1,00,000 default)
    monthly_limit       BIGINT DEFAULT 100000000,  -- in paise (₹10,00,000 default)
    per_transaction_limit BIGINT DEFAULT 5000000,  -- in paise (₹50,000 default)
    
    -- Fee structure
    fee_model           VARCHAR(50) DEFAULT 'PERCENTAGE', -- PERCENTAGE, FLAT, TIERED
    fee_percentage      DECIMAL(5,4) DEFAULT 0.0200,      -- 2% default
    fee_flat_amount     INTEGER DEFAULT 0,                 -- flat fee in paise
    
    metadata            JSONB DEFAULT '{}',
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT chk_merchant_status CHECK (status IN ('PENDING_KYC', 'KYC_SUBMITTED', 'ACTIVE', 'SUSPENDED', 'TERMINATED')),
    CONSTRAINT chk_merchant_business_type CHECK (business_type IN ('INDIVIDUAL', 'PARTNERSHIP', 'COMPANY', 'TRUST', 'NGO')),
    CONSTRAINT chk_merchant_fee CHECK (fee_percentage >= 0 AND fee_percentage <= 0.1000)
);

CREATE INDEX idx_merchants_user ON merchants(user_id);
CREATE INDEX idx_merchants_status ON merchants(status);
CREATE INDEX idx_merchants_business_name ON merchants(business_name);

COMMENT ON TABLE merchants IS 'Merchant business profiles with KYC, limits, and fee configuration';
COMMENT ON COLUMN merchants.daily_limit IS 'Maximum daily transaction volume in paise. Default ₹1,00,000';
COMMENT ON COLUMN merchants.fee_percentage IS 'Transaction fee as decimal. 0.0200 = 2%';


-- API Keys: authentication credentials for server-to-server integration
CREATE TABLE api_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    key_id          VARCHAR(50) NOT NULL,         -- public identifier (key_live_xxx / key_test_xxx)
    key_hash        VARCHAR(255) NOT NULL,        -- bcrypt hash of the secret key
    label           VARCHAR(255),                 -- human-readable label
    environment     VARCHAR(10) NOT NULL,         -- 'live' or 'test'
    
    -- Permissions
    permissions     JSONB DEFAULT '["*"]',        -- granular permissions array
    ip_whitelist    JSONB DEFAULT '[]',           -- allowed IPs (empty = all)
    
    -- Status
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    last_used_at    TIMESTAMP WITH TIME ZONE,
    last_used_ip    INET,
    usage_count     BIGINT DEFAULT 0,
    
    -- Expiry
    expires_at      TIMESTAMP WITH TIME ZONE,    -- NULL = never expires
    
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    revoked_at      TIMESTAMP WITH TIME ZONE,
    
    CONSTRAINT uq_api_keys_key_id UNIQUE (key_id),
    CONSTRAINT chk_api_keys_env CHECK (environment IN ('live', 'test'))
);

CREATE INDEX idx_api_keys_key_id ON api_keys(key_id) WHERE is_active = TRUE;
CREATE INDEX idx_api_keys_merchant ON api_keys(merchant_id) WHERE is_active = TRUE;

COMMENT ON TABLE api_keys IS 'Merchant API credentials. key_hash is bcrypt of actual secret.';
COMMENT ON COLUMN api_keys.key_id IS 'Public identifier safe to log. Format: key_{env}_{random}';
COMMENT ON COLUMN api_keys.key_hash IS 'NEVER store plain secret. Only hash for verification.';


-- Webhooks: merchant notification endpoints
CREATE TABLE webhooks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    url             VARCHAR(500) NOT NULL,        -- HTTPS endpoint
    secret          VARCHAR(255) NOT NULL,        -- for HMAC signature verification
    events          JSONB NOT NULL DEFAULT '["*"]', -- subscribed event types
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    
    -- Health tracking
    consecutive_failures INTEGER DEFAULT 0,
    last_success_at TIMESTAMP WITH TIME ZONE,
    last_failure_at TIMESTAMP WITH TIME ZONE,
    last_failure_reason VARCHAR(500),
    disabled_at     TIMESTAMP WITH TIME ZONE,     -- auto-disabled after 100 failures
    
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_webhooks_merchant ON webhooks(merchant_id) WHERE is_active = TRUE;

COMMENT ON TABLE webhooks IS 'Merchant webhook endpoints for event notifications';
COMMENT ON COLUMN webhooks.consecutive_failures IS 'Auto-disable after 100 consecutive failures';


-- Merchant configuration: runtime settings
CREATE TABLE merchant_configs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    
    -- Payment settings
    auto_capture    BOOLEAN DEFAULT FALSE,        -- auto-capture after auth
    capture_delay   INTEGER DEFAULT 0,            -- seconds to wait before auto-capture
    
    -- Settlement settings
    settlement_schedule VARCHAR(50) DEFAULT 'T+2', -- T+1, T+2, T+3
    
    -- Retry settings
    max_retries     INTEGER DEFAULT 3,
    retry_interval  INTEGER DEFAULT 300,          -- seconds between retries
    
    -- Notification preferences
    email_notifications BOOLEAN DEFAULT TRUE,
    sms_notifications   BOOLEAN DEFAULT FALSE,
    
    -- Custom settings (extensible)
    custom_settings JSONB DEFAULT '{}',
    
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_merchant_configs UNIQUE (merchant_id)
);

COMMENT ON TABLE merchant_configs IS 'Per-merchant runtime configuration. Cached in Redis.';
```

---

### Payment Database — `payment_db`

```sql
-- ============================================================
-- DATABASE: payment_db
-- SERVICE:  Payment Service
-- PURPOSE:  Orders, payments, refunds, and transaction processing
-- ============================================================

-- Orders: payment intent created by merchant
CREATE TABLE orders (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID NOT NULL,                -- logical FK to merchant_db
    
    -- Order details
    amount          BIGINT NOT NULL,              -- in smallest currency unit (paise)
    currency        VARCHAR(3) NOT NULL DEFAULT 'INR', -- ISO 4217
    receipt         VARCHAR(255),                 -- merchant's order reference
    
    -- Status
    status          VARCHAR(50) NOT NULL DEFAULT 'CREATED',
                    -- CREATED, ATTEMPTED, AUTHORIZED, CAPTURED, REFUNDED, VOIDED, EXPIRED
    
    -- Amounts tracking
    amount_paid     BIGINT DEFAULT 0,            -- total captured amount
    amount_refunded BIGINT DEFAULT 0,            -- total refunded amount
    amount_due      BIGINT GENERATED ALWAYS AS (amount - amount_paid) STORED,
    
    -- Attempt tracking
    attempts        INTEGER DEFAULT 0,           -- number of payment attempts
    max_attempts    INTEGER DEFAULT 5,           -- max allowed attempts
    
    -- Expiry
    expires_at      TIMESTAMP WITH TIME ZONE,    -- order expiry (default 30 min)
    
    -- Notes (merchant custom data)
    notes           JSONB DEFAULT '{}',
    
    -- Timestamps
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT chk_orders_amount CHECK (amount > 0),
    CONSTRAINT chk_orders_currency CHECK (currency IN ('INR', 'USD', 'EUR', 'GBP')),
    CONSTRAINT chk_orders_status CHECK (status IN ('CREATED', 'ATTEMPTED', 'AUTHORIZED', 'CAPTURED', 'REFUNDED', 'VOIDED', 'EXPIRED'))
);

-- Primary query pattern: merchant looking at their orders
CREATE INDEX idx_orders_merchant_status ON orders(merchant_id, status, created_at DESC);
-- For expiry cleanup job
CREATE INDEX idx_orders_expires ON orders(expires_at) WHERE status = 'CREATED';
-- For receipt-based lookup
CREATE INDEX idx_orders_merchant_receipt ON orders(merchant_id, receipt);

COMMENT ON TABLE orders IS 'Payment orders/intents. An order can have multiple payment attempts.';
COMMENT ON COLUMN orders.amount IS 'In smallest currency unit. For INR: paise. ₹500.00 = 50000';


-- Payments: individual payment attempts against an order
CREATE TABLE payments (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id            UUID NOT NULL REFERENCES orders(id),
    merchant_id         UUID NOT NULL,            -- denormalized for query performance
    
    -- Payment details
    amount              BIGINT NOT NULL,          -- authorized amount (paise)
    currency            VARCHAR(3) NOT NULL DEFAULT 'INR',
    
    -- Status
    status              VARCHAR(50) NOT NULL DEFAULT 'CREATED',
                        -- CREATED, AUTHORIZING, AUTHORIZED, CAPTURED, FAILED, DECLINED, VOIDED
    failure_reason      VARCHAR(500),             -- populated on FAILED/DECLINED
    failure_code        VARCHAR(50),              -- machine-readable error code
    
    -- Payment method details
    method              VARCHAR(50) NOT NULL,     -- card, upi, netbanking, wallet
    card_id             UUID,                     -- FK to cards table (if card payment)
    card_last4          VARCHAR(4),               -- denormalized for display
    card_network        VARCHAR(20),              -- visa, mastercard, rupay
    card_issuer         VARCHAR(255),             -- issuing bank name
    upi_id              VARCHAR(255),             -- VPA for UPI payments
    
    -- Acquirer details
    acquirer_id         VARCHAR(50),              -- which bank processed this
    acquirer_ref        VARCHAR(255),             -- bank's reference number (RRN)
    auth_code           VARCHAR(10),              -- authorization code from bank
    
    -- 3D Secure
    is_3ds              BOOLEAN DEFAULT FALSE,
    three_ds_status     VARCHAR(20),             -- ATTEMPTED, SUCCESS, FAILED
    
    -- Amounts
    captured_amount     BIGINT DEFAULT 0,
    refunded_amount     BIGINT DEFAULT 0,
    fee_amount          BIGINT DEFAULT 0,        -- our fee (calculated on capture)
    tax_amount          BIGINT DEFAULT 0,        -- GST on fee
    
    -- Fraud scoring
    fraud_score         DECIMAL(5,2),            -- 0.00 to 100.00
    fraud_flags         JSONB DEFAULT '[]',      -- list of triggered rules
    
    -- Metadata
    ip_address          INET,                    -- customer IP
    user_agent          TEXT,                    -- customer browser
    notes               JSONB DEFAULT '{}',
    
    -- Timestamps
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    authorized_at       TIMESTAMP WITH TIME ZONE,
    captured_at         TIMESTAMP WITH TIME ZONE,
    failed_at           TIMESTAMP WITH TIME ZONE,
    voided_at           TIMESTAMP WITH TIME ZONE,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT chk_payments_amount CHECK (amount > 0),
    CONSTRAINT chk_payments_status CHECK (status IN ('CREATED', 'AUTHORIZING', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'DECLINED', 'VOIDED')),
    CONSTRAINT chk_payments_method CHECK (method IN ('card', 'upi', 'netbanking', 'wallet'))
);

-- Primary query patterns
CREATE INDEX idx_payments_order ON payments(order_id);
CREATE INDEX idx_payments_merchant_status ON payments(merchant_id, status, created_at DESC);
CREATE INDEX idx_payments_acquirer_ref ON payments(acquirer_ref) WHERE acquirer_ref IS NOT NULL;
-- For settlement batch queries
CREATE INDEX idx_payments_captured_date ON payments(captured_at::DATE, merchant_id) 
    WHERE status = 'CAPTURED';
-- For fraud analysis
CREATE INDEX idx_payments_fraud_score ON payments(fraud_score DESC) 
    WHERE fraud_score > 70;

COMMENT ON TABLE payments IS 'Individual payment attempts. Multiple attempts possible per order.';
COMMENT ON COLUMN payments.acquirer_ref IS 'Bank reference (RRN). Unique per bank transaction.';


-- Refunds: refund records against captured payments
CREATE TABLE refunds (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id      UUID NOT NULL REFERENCES payments(id),
    merchant_id     UUID NOT NULL,               -- denormalized
    
    -- Refund details
    amount          BIGINT NOT NULL,             -- refund amount (paise)
    currency        VARCHAR(3) NOT NULL DEFAULT 'INR',
    reason          VARCHAR(500),                -- merchant-provided reason
    
    -- Status
    status          VARCHAR(50) NOT NULL DEFAULT 'CREATED',
                    -- CREATED, PROCESSING, PROCESSED, FAILED
    failure_reason  VARCHAR(500),
    
    -- Bank details
    acquirer_ref    VARCHAR(255),                -- bank's refund reference
    
    -- Speed
    speed           VARCHAR(20) DEFAULT 'NORMAL', -- NORMAL (5-7 days), INSTANT
    
    -- Metadata
    notes           JSONB DEFAULT '{}',
    
    -- Timestamps
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMP WITH TIME ZONE,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT chk_refunds_amount CHECK (amount > 0),
    CONSTRAINT chk_refunds_status CHECK (status IN ('CREATED', 'PROCESSING', 'PROCESSED', 'FAILED'))
);

CREATE INDEX idx_refunds_payment ON refunds(payment_id);
CREATE INDEX idx_refunds_merchant ON refunds(merchant_id, created_at DESC);
CREATE INDEX idx_refunds_status ON refunds(status) WHERE status IN ('CREATED', 'PROCESSING');

COMMENT ON TABLE refunds IS 'Refund records. Multiple partial refunds allowed per payment.';


-- Payment events: immutable event log (event sourcing)
CREATE TABLE payment_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id      UUID NOT NULL REFERENCES payments(id),
    
    -- Event details
    event_type      VARCHAR(100) NOT NULL,       -- CREATED, AUTHORIZED, CAPTURED, etc.
    from_status     VARCHAR(50),                 -- previous status
    to_status       VARCHAR(50) NOT NULL,        -- new status
    
    -- Event data
    data            JSONB DEFAULT '{}',          -- event-specific payload
    
    -- Actor
    actor_type      VARCHAR(50),                 -- SYSTEM, MERCHANT, BANK, ADMIN
    actor_id        VARCHAR(255),                -- who triggered this event
    
    -- Timestamp (immutable — no updated_at)
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- For payment timeline queries
CREATE INDEX idx_payment_events_payment ON payment_events(payment_id, created_at ASC);
-- For event replay/debugging
CREATE INDEX idx_payment_events_type ON payment_events(event_type, created_at DESC);

COMMENT ON TABLE payment_events IS 'Immutable event log. Never UPDATE or DELETE. Used for audit trail and event replay.';


-- Idempotency keys: prevent duplicate processing
CREATE TABLE idempotency_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key             VARCHAR(255) NOT NULL,       -- client-provided idempotency key
    merchant_id     UUID NOT NULL,
    
    -- Request/Response storage
    request_path    VARCHAR(500) NOT NULL,       -- API endpoint
    request_hash    VARCHAR(64) NOT NULL,        -- SHA-256 of request body
    response_code   INTEGER,                     -- HTTP status code
    response_body   JSONB,                       -- cached response
    
    -- Status
    status          VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
                    -- PROCESSING, COMPLETED, ERROR
    
    -- Timestamps
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (NOW() + INTERVAL '24 hours'),
    
    CONSTRAINT uq_idempotency_merchant_key UNIQUE (merchant_id, key)
);

-- Primary lookup pattern
CREATE INDEX idx_idempotency_lookup ON idempotency_keys(merchant_id, key) 
    WHERE status != 'ERROR';
-- For cleanup job
CREATE INDEX idx_idempotency_expiry ON idempotency_keys(expires_at);

COMMENT ON TABLE idempotency_keys IS 'Stores request/response for idempotent replay. TTL: 24 hours.';
COMMENT ON COLUMN idempotency_keys.request_hash IS 'Detects body mismatch on same key (409 Conflict).';
```

---

### Settlement Database — `settlement_db`

```sql
-- ============================================================
-- DATABASE: settlement_db
-- SERVICE:  Settlement Service
-- PURPOSE:  Batch settlement processing and reconciliation
-- ============================================================

-- Settlement batches: daily settlement runs
CREATE TABLE settlement_batches (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Batch identification
    batch_date      DATE NOT NULL,               -- settlement date
    batch_number    INTEGER NOT NULL,            -- sequence number for that date
    
    -- Aggregates
    total_amount    BIGINT NOT NULL DEFAULT 0,   -- total settlement amount (paise)
    total_fee       BIGINT NOT NULL DEFAULT 0,   -- total fees collected
    total_tax       BIGINT NOT NULL DEFAULT 0,   -- total GST on fees
    net_amount      BIGINT NOT NULL DEFAULT 0,   -- amount to transfer to merchants
    transaction_count INTEGER NOT NULL DEFAULT 0,
    merchant_count  INTEGER NOT NULL DEFAULT 0,
    
    -- Status
    status          VARCHAR(50) NOT NULL DEFAULT 'CREATED',
                    -- CREATED, PROCESSING, COMPLETED, PARTIALLY_FAILED, FAILED
    
    -- Processing details
    started_at      TIMESTAMP WITH TIME ZONE,
    completed_at    TIMESTAMP WITH TIME ZONE,
    error_details   JSONB DEFAULT '{}',
    
    -- Metadata
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_batch_date_number UNIQUE (batch_date, batch_number),
    CONSTRAINT chk_batch_status CHECK (status IN ('CREATED', 'PROCESSING', 'COMPLETED', 'PARTIALLY_FAILED', 'FAILED'))
);

CREATE INDEX idx_batches_date ON settlement_batches(batch_date DESC);
CREATE INDEX idx_batches_status ON settlement_batches(status) WHERE status != 'COMPLETED';

COMMENT ON TABLE settlement_batches IS 'Daily settlement batch runs. One or more per day.';


-- Merchant settlements: per-merchant amounts in a batch
CREATE TABLE merchant_settlements (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id        UUID NOT NULL REFERENCES settlement_batches(id),
    merchant_id     UUID NOT NULL,
    
    -- Amounts
    gross_amount    BIGINT NOT NULL,            -- total captured amount
    fee_amount      BIGINT NOT NULL,            -- our fee
    tax_amount      BIGINT NOT NULL,            -- GST on fee
    net_amount      BIGINT NOT NULL,            -- amount to transfer
    adjustment      BIGINT DEFAULT 0,           -- chargebacks, refund clawbacks
    
    -- Transfer details
    bank_reference  VARCHAR(255),               -- bank transfer reference (UTR)
    transfer_status VARCHAR(50) DEFAULT 'PENDING',
                    -- PENDING, INITIATED, COMPLETED, FAILED
    
    -- Counts
    transaction_count INTEGER NOT NULL DEFAULT 0,
    refund_count     INTEGER DEFAULT 0,
    
    -- Timestamps
    transferred_at  TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_merchant_settlement UNIQUE (batch_id, merchant_id)
);

CREATE INDEX idx_merchant_settlements_merchant ON merchant_settlements(merchant_id, created_at DESC);
CREATE INDEX idx_merchant_settlements_status ON merchant_settlements(transfer_status) 
    WHERE transfer_status != 'COMPLETED';

COMMENT ON TABLE merchant_settlements IS 'Per-merchant settlement amounts within a batch.';


-- Reconciliation records
CREATE TABLE reconciliation_records (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id        UUID NOT NULL REFERENCES settlement_batches(id),
    payment_id      UUID NOT NULL,              -- payment being reconciled
    
    -- Our record
    our_amount      BIGINT NOT NULL,
    our_status      VARCHAR(50) NOT NULL,
    
    -- Bank record
    bank_amount     BIGINT,
    bank_status     VARCHAR(50),
    bank_reference  VARCHAR(255),
    
    -- Match result
    match_status    VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                    -- MATCHED, AMOUNT_MISMATCH, MISSING_AT_BANK, MISSING_AT_US, PENDING
    discrepancy     BIGINT DEFAULT 0,           -- difference in paise
    
    -- Resolution
    resolved        BOOLEAN DEFAULT FALSE,
    resolved_by     UUID,
    resolved_at     TIMESTAMP WITH TIME ZONE,
    resolution_notes TEXT,
    
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_recon_batch ON reconciliation_records(batch_id);
CREATE INDEX idx_recon_unresolved ON reconciliation_records(match_status) 
    WHERE resolved = FALSE AND match_status != 'MATCHED';

COMMENT ON TABLE reconciliation_records IS 'Compare our records with bank records. Flag discrepancies.';
```

---

### ER Diagram (ASCII)

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                           ENTITY RELATIONSHIP DIAGRAM                              │
├──────────────────────────────────────────────────────────────────────────────────┤
│                                                                                    │
│  IDENTITY_DB                    MERCHANT_DB                                        │
│  ══════════                     ═══════════                                        │
│                                                                                    │
│  ┌──────────┐                   ┌────────────┐                                    │
│  │  users   │───────────────────│ merchants  │                                    │
│  │──────────│  1            1   │────────────│                                    │
│  │ id (PK)  │                   │ id (PK)    │                                    │
│  │ email    │                   │ user_id    │──┐                                 │
│  │ role     │                   │ status     │  │                                 │
│  └────┬─────┘                   └──────┬─────┘  │                                 │
│       │ 1                              │ 1      │                                 │
│       │                                │        │                                 │
│       │ N                              │ N      │                                 │
│  ┌────▼─────────┐              ┌───────▼──────┐ │                                 │
│  │refresh_tokens│              │   api_keys   │ │                                 │
│  │──────────────│              │──────────────│ │                                 │
│  │ id (PK)      │              │ id (PK)     │ │                                 │
│  │ user_id (FK) │              │ merchant_id │ │                                 │
│  │ token_hash   │              │ key_id      │ │                                 │
│  └──────────────┘              │ key_hash    │ │                                 │
│                                └──────────────┘ │                                 │
│                                                  │                                 │
│                                ┌───────────────┐│                                 │
│                                │   webhooks    ││                                 │
│                                │───────────────││                                 │
│                                │ id (PK)      ││                                 │
│                                │ merchant_id  │┘                                  │
│                                │ url          │                                   │
│                                │ events       │                                   │
│                                └───────────────┘                                  │
│                                                                                    │
│  PAYMENT_DB                                                                        │
│  ══════════                                                                        │
│                                                                                    │
│  ┌────────────┐         ┌─────────────┐         ┌──────────────┐                 │
│  │   orders   │─────────│  payments   │─────────│   refunds    │                 │
│  │────────────│ 1     N │─────────────│ 1     N │──────────────│                 │
│  │ id (PK)    │         │ id (PK)     │         │ id (PK)      │                 │
│  │ merchant_id│         │ order_id(FK)│         │ payment_id(FK│                 │
│  │ amount     │         │ amount      │         │ amount       │                 │
│  │ status     │         │ status      │         │ status       │                 │
│  └────────────┘         │ method      │         └──────────────┘                 │
│                         └──────┬──────┘                                           │
│                                │ 1                                                 │
│                                │                                                   │
│                                │ N                                                 │
│                         ┌──────▼──────────┐                                       │
│                         │ payment_events  │                                       │
│                         │─────────────────│                                       │
│                         │ id (PK)         │                                       │
│                         │ payment_id (FK) │                                       │
│                         │ event_type      │                                       │
│                         │ from_status     │                                       │
│                         │ to_status       │                                       │
│                         └─────────────────┘                                       │
│                                                                                    │
│  ┌──────────────────┐                                                             │
│  │ idempotency_keys │                                                             │
│  │──────────────────│                                                             │
│  │ id (PK)          │                                                             │
│  │ merchant_id      │                                                             │
│  │ key (UNIQUE)     │                                                             │
│  │ response_body    │                                                             │
│  └──────────────────┘                                                             │
│                                                                                    │
│  SETTLEMENT_DB                                                                     │
│  ═════════════                                                                     │
│                                                                                    │
│  ┌────────────────────┐    ┌─────────────────────┐    ┌─────────────────────┐     │
│  │ settlement_batches │────│ merchant_settlements│────│reconciliation_records│    │
│  │────────────────────│1  N│─────────────────────│    │─────────────────────│    │
│  │ id (PK)            │    │ id (PK)             │    │ id (PK)             │    │
│  │ batch_date         │    │ batch_id (FK)       │    │ batch_id (FK)       │    │
│  │ total_amount       │    │ merchant_id         │    │ payment_id          │    │
│  │ status             │    │ net_amount          │    │ match_status        │    │
│  └────────────────────┘    └─────────────────────┘    └─────────────────────┘    │
│                                                                                    │
└──────────────────────────────────────────────────────────────────────────────────┘
```

---

## 📡 ISO 8583 Protocol

### What is ISO 8583? (Explained from Zero)

ISO 8583 is the international standard for financial transaction card-originated messages. If you've ever swiped a credit card, the communication between the card terminal and the bank happened using ISO 8583.

**Think of it as:** The HTTP of the banking world — a structured binary protocol that every bank and card network understands.

### Why Binary? Why Not JSON?

```
┌─────────────────────────────────────────────────────────────────┐
│ WHY ISO 8583 IS BINARY (NOT TEXT-BASED)                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  1. DESIGNED IN 1987 — bandwidth was expensive                   │
│  2. PERFORMANCE — binary parsing is 10x faster than JSON parsing │
│  3. FIXED STRUCTURE — deterministic parsing, no ambiguity        │
│  4. COMPACT — a full auth message is ~300 bytes (vs ~2KB JSON)   │
│  5. LEGACY — changing would require updating millions of devices │
│                                                                   │
│  JSON equivalent of ISO 8583 auth message:                       │
│  ┌────────────────────────────┐   ┌───────────────────┐         │
│  │ JSON: ~2,048 bytes         │   │ ISO 8583: ~300 B  │         │
│  │ Parse time: ~0.5ms         │   │ Parse time: ~0.05ms│        │
│  │ Human readable: ✅         │   │ Human readable: ❌ │        │
│  │ Self-describing: ✅        │   │ Requires spec: ✅  │        │
│  └────────────────────────────┘   └───────────────────┘         │
│                                                                   │
│  At 65,000 TPS (Visa), saving 1.7KB per message saves:          │
│  65,000 × 1,700 bytes = 110 MB/second of bandwidth              │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

### ISO 8583 Message Structure

Every ISO 8583 message has exactly 3 parts:

```
┌──────────────────────────────────────────────────────────────────┐
│                    ISO 8583 MESSAGE STRUCTURE                      │
├──────────────────────────────────────────────────────────────────┤
│                                                                    │
│  ┌─────────┐  ┌──────────────────────┐  ┌────────────────────┐  │
│  │   MTI   │  │       BITMAP         │  │    DATA ELEMENTS   │  │
│  │ 4 bytes │  │   8 or 16 bytes      │  │   Variable length  │  │
│  └─────────┘  └──────────────────────┘  └────────────────────┘  │
│                                                                    │
│  MTI: Message Type Indicator                                       │
│       - Tells you WHAT kind of message this is                    │
│       - 4 digits: Version + Class + Function + Origin             │
│                                                                    │
│  BITMAP: Which fields are present                                  │
│       - 64 or 128 bits (primary + secondary bitmap)               │
│       - Each bit = 1 means that field number is present           │
│       - Bit 1 set → Field 1 present in data elements             │
│                                                                    │
│  DATA ELEMENTS: The actual field values                            │
│       - Fields 1-128 (only those indicated by bitmap)             │
│       - Each field has a defined type, length, and encoding       │
│                                                                    │
└──────────────────────────────────────────────────────────────────┘
```

### Message Type Indicator (MTI) — Decoded

The MTI is 4 digits, each with specific meaning:

```
  Position 1: VERSION         Position 2: CLASS           Position 3: FUNCTION        Position 4: ORIGIN
  ──────────────────         ───────────────            ────────────────────        ──────────────────
  0 = ISO 8583:1987          1 = Authorization          0 = Request                 0 = Acquirer
  1 = ISO 8583:1993          2 = Financial              1 = Response                1 = Acquirer repeat
  2 = ISO 8583:2003          3 = File actions           2 = Advice                  2 = Issuer
                             4 = Reversal/Chargeback    3 = Advice response         3 = Issuer repeat
                             5 = Reconciliation         4 = Notification            4 = Other
                             8 = Network management     5 = Notification ack        5 = Other repeat
```

### Common MTI Codes

| MTI | Meaning | When Used |
|-----|---------|-----------|
| `0100` | Authorization Request | Terminal sends to bank: "Can this card pay ₹500?" |
| `0110` | Authorization Response | Bank responds: "Yes, auth code 123456" or "Declined" |
| `0200` | Financial Transaction Request | Combined auth + capture in one message |
| `0210` | Financial Transaction Response | Response to financial transaction |
| `0220` | Financial Transaction Advice | Notification that transaction happened (store & forward) |
| `0230` | Financial Transaction Advice Response | Acknowledgment of advice |
| `0400` | Reversal Request | Cancel a previous authorization |
| `0410` | Reversal Response | Acknowledgment of reversal |
| `0420` | Reversal Advice | Reversal notification (store & forward) |
| `0430` | Reversal Advice Response | Acknowledgment |
| `0500` | Reconciliation Request | End-of-day settlement totals |
| `0510` | Reconciliation Response | Bank confirms settlement totals |
| `0800` | Network Management Request | Echo test / sign-on / sign-off |
| `0810` | Network Management Response | Response to network management |

### Key Data Elements (Fields)

| Field # | Name | Type | Length | Description | Example |
|---------|------|------|--------|-------------|---------|
| 2 | Primary Account Number (PAN) | N | 19 | Card number | `4111111111111111` |
| 3 | Processing Code | N | 6 | Transaction type | `000000` (purchase) |
| 4 | Amount, Transaction | N | 12 | Amount in smallest unit | `000000050000` (₹500) |
| 7 | Transmission Date & Time | N | 10 | MMDDhhmmss | `0115103000` |
| 11 | Systems Trace Audit Number (STAN) | N | 6 | Unique trace number | `123456` |
| 12 | Local Transaction Time | N | 6 | hhmmss | `103000` |
| 13 | Local Transaction Date | N | 4 | MMDD | `0115` |
| 14 | Expiration Date | N | 4 | YYMM | `2512` (Dec 2025) |
| 22 | Point of Service Entry Mode | N | 3 | How card was read | `051` (chip) |
| 23 | Card Sequence Number | N | 3 | For multi-card accounts | `001` |
| 25 | Point of Service Condition Code | N | 2 | Transaction condition | `00` (normal) |
| 32 | Acquiring Institution ID | N | 11 | Acquirer bank code | `12345678901` |
| 35 | Track 2 Data | Z | 37 | Magnetic stripe data | (card track data) |
| 37 | Retrieval Reference Number (RRN) | AN | 12 | Transaction reference | `401512345678` |
| 38 | Authorization ID Response | AN | 6 | Auth code from issuer | `A12345` |
| 39 | Response Code | AN | 2 | Result code | `00` (approved) |
| 41 | Card Acceptor Terminal ID | ANS | 8 | Terminal identifier | `TERM0001` |
| 42 | Card Acceptor ID Code | ANS | 15 | Merchant ID | `MERCHANT001    ` |
| 43 | Card Acceptor Name/Location | ANS | 40 | Merchant name & address | `ACME CORP MUMBAI IN` |
| 48 | Additional Data | ANS | 999 | Private use (variable) | (custom fields) |
| 49 | Currency Code, Transaction | N | 3 | ISO 4217 code | `356` (INR) |
| 52 | PIN Data | B | 8 | Encrypted PIN block | (binary PIN block) |
| 54 | Additional Amounts | AN | 120 | Balance, cashback, etc. | Amount details |
| 55 | ICC Data (EMV) | ANS | 999 | Chip card data (TLV) | (EMV tag data) |
| 60 | Private Use | ANS | 999 | Network-specific data | (varies) |
| 63 | Private Use | ANS | 999 | Network-specific data | (varies) |

### Response Codes (Field 39)

| Code | Meaning | Action |
|------|---------|--------|
| `00` | Approved | Transaction successful |
| `01` | Refer to card issuer | Call bank for voice auth |
| `05` | Do not honor | Generic decline |
| `12` | Invalid transaction | Wrong transaction type |
| `13` | Invalid amount | Amount out of range |
| `14` | Invalid card number | Card number not found |
| `30` | Format error | Message format problem |
| `41` | Lost card — pick up | Card reported lost |
| `43` | Stolen card — pick up | Card reported stolen |
| `51` | Insufficient funds | Not enough balance |
| `54` | Expired card | Card has expired |
| `55` | Incorrect PIN | Wrong PIN entered |
| `57` | Transaction not allowed | Card restrictions |
| `61` | Exceeds withdrawal limit | Over daily/per-txn limit |
| `65` | Exceeds withdrawal frequency | Too many transactions |
| `91` | Issuer unavailable | Bank system is down |
| `96` | System malfunction | General system error |

### Example: Authorization Request Walkthrough

Let's trace a complete ₹500 card payment:

```
┌──────────────────────────────────────────────────────────────────────┐
│ SCENARIO: Customer pays ₹500 with Visa card at Acme Corp            │
├──────────────────────────────────────────────────────────────────────┤
│                                                                        │
│ STEP 1: Our system builds ISO 8583 authorization request              │
│                                                                        │
│ ┌─────────────────────────────────────────────────────────────────┐  │
│ │ MTI: 0100 (Authorization Request, ISO 8583:1987, from Acquirer) │  │
│ │                                                                   │  │
│ │ BITMAP (in binary):                                               │  │
│ │ 0111001000111000100000001000000000000000000000100000000000000000  │  │
│ │ ↑Bit 2,3,4,7,11,12,13,14,22,25,32,37,41,42,43,49              │  │
│ │                                                                   │  │
│ │ DATA ELEMENTS:                                                    │  │
│ │ Field 02 [PAN]:            4111111111111111                       │  │
│ │ Field 03 [Processing Code]: 000000 (Purchase)                    │  │
│ │ Field 04 [Amount]:          000000050000 (₹500.00 = 50000 paise) │  │
│ │ Field 07 [DateTime]:        0115103000 (Jan 15, 10:30:00)        │  │
│ │ Field 11 [STAN]:            123456                                │  │
│ │ Field 12 [Time]:            103000                                │  │
│ │ Field 13 [Date]:            0115                                  │  │
│ │ Field 14 [Expiry]:          2512 (Dec 2025)                      │  │
│ │ Field 22 [Entry Mode]:      051 (Chip/ICC)                       │  │
│ │ Field 25 [Condition Code]:  00 (Normal)                          │  │
│ │ Field 32 [Acquirer ID]:     12345678901                          │  │
│ │ Field 37 [RRN]:             401512345678                         │  │
│ │ Field 41 [Terminal ID]:     TERM0001                             │  │
│ │ Field 42 [Merchant ID]:     ACMECORP000001                      │  │
│ │ Field 43 [Merchant Name]:   ACME CORP MUMBAI MH IN              │  │
│ │ Field 49 [Currency]:        356 (INR)                            │  │
│ └─────────────────────────────────────────────────────────────────┘  │
│                                                                        │
│ STEP 2: Message sent over persistent TCP connection to bank           │
│                                                                        │
│ [PayFlow] ═══TCP═══► [Acquirer Bank] ═══Network═══► [Card Network]   │
│                                         (NPCI/Visa)                    │
│                                               ║                        │
│                                               ▼                        │
│                                        [Issuing Bank]                  │
│                                        Checks: Balance ✓               │
│                                        Checks: Fraud ✓                 │
│                                        Checks: Limits ✓                │
│                                        Result: APPROVED                │
│                                               ║                        │
│                                               ▼                        │
│ [PayFlow] ◄═══TCP═══ [Acquirer Bank] ◄═══Network═══                  │
│                                                                        │
│ STEP 3: Response received                                              │
│                                                                        │
│ ┌─────────────────────────────────────────────────────────────────┐  │
│ │ MTI: 0110 (Authorization Response)                               │  │
│ │                                                                   │  │
│ │ Key Response Fields:                                              │  │
│ │ Field 38 [Auth Code]:       A12345                               │  │
│ │ Field 39 [Response Code]:   00 (APPROVED)                        │  │
│ │ Field 37 [RRN]:             401512345678 (same as request)       │  │
│ └─────────────────────────────────────────────────────────────────┘  │
│                                                                        │
│ STEP 4: PayFlow updates payment status to AUTHORIZED                  │
│         Merchant receives webhook: payment.authorized                  │
│                                                                        │
│ Total time: ~150-300ms                                                 │
│                                                                        │
└──────────────────────────────────────────────────────────────────────┘
```

### How PayFlow Implements ISO 8583

In our system, the Bank Simulator service speaks ISO 8583. Here's the architecture:

```
┌─────────────────────────────────────────────────────────────────┐
│                    ISO 8583 IN PAYFLOW                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Payment Service (REST/JSON)                                     │
│       │                                                           │
│       │ Internal call with payment details                       │
│       ▼                                                           │
│  Payment Router                                                   │
│       │                                                           │
│       │ Routes to appropriate acquirer                            │
│       ▼                                                           │
│  ISO 8583 Codec (Netty Pipeline)                                 │
│       │                                                           │
│       ├── MessageEncoder: Java Object → ISO 8583 binary          │
│       │   1. Build MTI (0100 for auth)                           │
│       │   2. Set field values                                     │
│       │   3. Calculate bitmap from present fields                 │
│       │   4. Encode each field (BCD, ASCII, binary)              │
│       │   5. Prepend message length header                        │
│       │                                                           │
│       ├── MessageDecoder: ISO 8583 binary → Java Object          │
│       │   1. Read message length                                  │
│       │   2. Parse MTI                                            │
│       │   3. Parse bitmap (determine present fields)             │
│       │   4. Decode each field based on field spec               │
│       │   5. Build response object                                │
│       │                                                           │
│       ▼                                                           │
│  TCP Connection Pool (to Bank)                                   │
│       │                                                           │
│       │ Persistent TCP socket                                     │
│       ▼                                                           │
│  Bank Simulator / Real Acquiring Bank                            │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 📨 Kafka Topics Design

### Topic Naming Convention

Format: `{domain}.{entity}.{action}` or `{domain}.{event-type}`

### Topics Table

| Topic Name | Producer | Consumer(s) | Partition Key | Partitions | Retention | Description |
|-----------|----------|-------------|---------------|------------|-----------|-------------|
| `payment.order.created` | Payment Service | Notification, Analytics | `merchant_id` | 12 | 7 days | New order created |
| `payment.authorized` | Payment Service | Notification, Fraud, Analytics | `merchant_id` | 12 | 7 days | Payment authorized by bank |
| `payment.captured` | Payment Service | Settlement, Notification, Analytics | `merchant_id` | 12 | 30 days | Payment captured |
| `payment.failed` | Payment Service | Notification, Analytics, Fraud | `merchant_id` | 12 | 7 days | Payment failed/declined |
| `payment.voided` | Payment Service | Notification, Analytics | `merchant_id` | 12 | 7 days | Authorization voided |
| `payment.refund.created` | Payment Service | Notification, Analytics | `merchant_id` | 6 | 7 days | Refund initiated |
| `payment.refund.processed` | Payment Service | Notification, Settlement | `merchant_id` | 6 | 7 days | Refund completed |
| `merchant.registered` | Merchant Service | Notification, Analytics | `merchant_id` | 3 | 7 days | New merchant registered |
| `merchant.activated` | Merchant Service | Notification | `merchant_id` | 3 | 7 days | Merchant KYC approved |
| `merchant.suspended` | Merchant Service | Payment Service, Notification | `merchant_id` | 3 | 30 days | Merchant suspended |
| `settlement.batch.created` | Settlement Service | Notification | `batch_id` | 3 | 30 days | Settlement batch started |
| `settlement.batch.completed` | Settlement Service | Notification, Analytics | `batch_id` | 3 | 30 days | Settlement completed |
| `settlement.merchant.transferred` | Settlement Service | Notification | `merchant_id` | 6 | 30 days | Merchant payout initiated |
| `fraud.alert.high` | Fraud Engine | Payment Service, Notification | `merchant_id` | 6 | 30 days | High-risk transaction detected |
| `fraud.alert.blocked` | Fraud Engine | Payment Service, Notification | `payment_id` | 6 | 30 days | Transaction blocked by fraud |
| `webhook.delivery.requested` | All Services | Notification Service | `merchant_id` | 12 | 3 days | Webhook delivery request |
| `webhook.delivery.failed` | Notification | Notification (retry) | `merchant_id` | 6 | 7 days | Failed webhook (for retry) |
| `notification.email.send` | All Services | Notification Service | `user_id` | 6 | 3 days | Email delivery request |
| `notification.sms.send` | All Services | Notification Service | `user_id` | 3 | 3 days | SMS delivery request |
| `audit.event` | All Services | Audit Service | `service_name` | 6 | 90 days | System-wide audit events |

### Partition Strategy

```
┌─────────────────────────────────────────────────────────────────┐
│ KAFKA PARTITIONING STRATEGY                                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│ WHY merchant_id AS PARTITION KEY?                                 │
│                                                                   │
│ 1. ORDERING: All events for a merchant are in same partition     │
│    → Guaranteed ordering per merchant                             │
│    → Settlement sees events in correct sequence                   │
│                                                                   │
│ 2. LOCALITY: Related events processed by same consumer instance  │
│    → Better cache utilization                                     │
│    → Simpler state management in consumers                        │
│                                                                   │
│ 3. SCALABILITY: Even distribution across partitions              │
│    → merchant_id (UUID) hashes uniformly                          │
│    → No hot partitions from large merchants                       │
│                                                                   │
│ PARTITION COUNT REASONING:                                        │
│                                                                   │
│ payment.* topics: 12 partitions                                   │
│   - 1000 TPS target / 12 = ~83 messages per partition per second │
│   - Allows up to 12 consumer instances for parallelism           │
│   - Can increase to 24/48 at scale without rebalancing           │
│                                                                   │
│ settlement.* topics: 3 partitions                                 │
│   - Low volume (1 batch per day per merchant)                    │
│   - 3 partitions for HA (replication factor 3)                   │
│                                                                   │
│ CONSUMER GROUP DESIGN:                                            │
│                                                                   │
│ ┌────────────────┐     ┌─────────────────────────────┐          │
│ │ payment.captured│────►│ CG: settlement-service       │          │
│ │ (12 partitions) │────►│ CG: notification-service     │          │
│ │                 │────►│ CG: analytics-service        │          │
│ └────────────────┘     └─────────────────────────────┘          │
│                                                                   │
│ Each consumer group independently tracks its offset.             │
│ Settlement can be behind without affecting notifications.         │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

### Kafka Configuration

| Setting | Value | Reasoning |
|---------|-------|-----------|
| Replication Factor | 3 | Survive 2 broker failures |
| Min In-Sync Replicas | 2 | No writes unless 2 replicas confirmed |
| acks | all | Producer waits for all ISR acknowledgment |
| enable.idempotence | true | Prevent duplicate messages from producer retries |
| max.in.flight.requests | 5 | With idempotence, safe for ordering |
| retention.ms | varies | 7-90 days per topic (see table) |
| compression.type | lz4 | Best throughput-to-compression ratio |
| batch.size | 32KB | Batch messages for efficiency |
| linger.ms | 5 | Wait 5ms to batch messages |

### Dead Letter Topic Pattern

```
For every consumer, failed messages go to a dead letter topic:

payment.captured → (consumer fails) → payment.captured.dlq

DLQ Processing:
1. Alert on DLQ message count > threshold
2. Manual investigation dashboard
3. Replay from DLQ after fix
4. After 7 days in DLQ, archive to cold storage
```

---

## 🔒 Security Design

### Security Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                       SECURITY LAYERS                                  │
├──────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  LAYER 1: Network Security                                            │
│  ├── TLS 1.3 everywhere (no plaintext)                               │
│  ├── mTLS between internal services                                   │
│  ├── Private subnets for databases and Kafka                         │
│  └── WAF (Web Application Firewall) at edge                         │
│                                                                        │
│  LAYER 2: Authentication                                              │
│  ├── JWT (RS256) for dashboard users                                 │
│  ├── API Key + HMAC for server-to-server                             │
│  ├── mTLS certificates for internal services                         │
│  └── Rate limiting at gateway level                                   │
│                                                                        │
│  LAYER 3: Authorization                                               │
│  ├── RBAC (Role-Based Access Control)                                │
│  ├── Resource-level permissions (merchant can only access own data)  │
│  ├── API key scoping (read-only, payments-only, etc.)                │
│  └── IP whitelist for API keys                                       │
│                                                                        │
│  LAYER 4: Data Security                                               │
│  ├── PCI-DSS compliance (never store CVV, mask PAN)                  │
│  ├── Encryption at rest (AES-256 for sensitive columns)              │
│  ├── Database column-level encryption for bank accounts              │
│  └── Audit logging for all data access                               │
│                                                                        │
│  LAYER 5: Application Security                                        │
│  ├── Input validation (strict schemas)                                │
│  ├── SQL injection prevention (parameterized queries only)           │
│  ├── CORS policy (whitelist merchant domains)                        │
│  └── Security headers (CSP, X-Frame-Options, HSTS)                  │
│                                                                        │
└──────────────────────────────────────────────────────────────────────┘
```

### JWT Authentication Flow

```
┌──────────────────────────────────────────────────────────────────────┐
│                    JWT AUTHENTICATION FLOW                             │
├──────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  ┌─────────┐         ┌─────────────┐         ┌──────────────┐       │
│  │ Client  │         │ API Gateway │         │Identity Service│      │
│  └────┬────┘         └──────┬──────┘         └──────┬────────┘      │
│       │                     │                        │                │
│  ═══ LOGIN FLOW ════════════════════════════════════════════════      │
│       │                     │                        │                │
│       │──POST /login───────►│                        │                │
│       │  {email, password}  │───────────────────────►│                │
│       │                     │                        │                │
│       │                     │                        │──Verify pwd    │
│       │                     │                        │  (bcrypt)      │
│       │                     │                        │                │
│       │                     │                        │──Generate JWT  │
│       │                     │                        │  (RS256)       │
│       │                     │                        │                │
│       │                     │                        │──Store refresh │
│       │                     │                        │  token in Redis│
│       │                     │◄───────────────────────│                │
│       │◄────────────────────│                        │                │
│       │  {access_token,     │                        │                │
│       │   refresh_token}    │                        │                │
│       │                     │                        │                │
│  ═══ API CALL FLOW ════════════════════════════════════════════       │
│       │                     │                        │                │
│       │──GET /payments──────►│                        │                │
│       │  Authorization:     │                        │                │
│       │  Bearer <jwt>       │                        │                │
│       │                     │──Validate JWT locally  │                │
│       │                     │  (verify RS256 sig)    │                │
│       │                     │  (check expiry)        │                │
│       │                     │  (extract claims)      │                │
│       │                     │                        │                │
│       │                     │  ✅ No DB call needed! │                │
│       │                     │                        │                │
│       │                     │──Route to service──────►│               │
│       │                     │  with X-User-Id,       │                │
│       │                     │  X-Merchant-Id,        │                │
│       │                     │  X-Role headers        │                │
│       │                     │                        │                │
│  ═══ REFRESH FLOW ═════════════════════════════════════════════       │
│       │                     │                        │                │
│       │──POST /refresh──────►│                        │                │
│       │  {refresh_token}    │───────────────────────►│                │
│       │                     │                        │──Check Redis   │
│       │                     │                        │  (not revoked?)│
│       │                     │                        │──Rotate token  │
│       │                     │                        │  (new refresh) │
│       │                     │◄───────────────────────│                │
│       │◄────────────────────│  {new_access,          │                │
│       │                     │   new_refresh}         │                │
│       │                     │                        │                │
└──────────────────────────────────────────────────────────────────────┘
```

### JWT Token Structure

```json
// HEADER
{
  "alg": "RS256",
  "typ": "JWT",
  "kid": "key-2024-01"          // Key ID for rotation
}

// PAYLOAD (Claims)
{
  "sub": "user_a1b2c3d4",       // Subject (user ID)
  "merchant_id": "merch_x1y2z3", // Merchant context
  "role": "MERCHANT",            // Role for RBAC
  "permissions": ["payments:*", "orders:*"], // Fine-grained
  "iat": 1705312200,            // Issued at
  "exp": 1705313100,            // Expires (15 min later)
  "iss": "payflow-identity",    // Issuer
  "aud": "payflow-api"          // Audience
}

// SIGNATURE
RS256(base64(header) + "." + base64(payload), privateKey)
```

### API Key + HMAC Validation Flow

```
┌──────────────────────────────────────────────────────────────────────┐
│                    API KEY + HMAC VALIDATION                           │
├──────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  MERCHANT SERVER:                                                      │
│                                                                        │
│  1. Prepare request:                                                   │
│     body = '{"amount":50000,"currency":"INR"}'                        │
│     timestamp = "2024-01-15T10:30:00Z"                                │
│                                                                        │
│  2. Build signature string:                                            │
│     string_to_sign = timestamp + "|" + "POST" + "|"                  │
│                      + "/v1/payments/orders" + "|"                │
│                      + SHA256(body)                                    │
│                                                                        │
│  3. Calculate HMAC:                                                    │
│     signature = HMAC-SHA256(string_to_sign, api_secret)              │
│                                                                        │
│  4. Send request:                                                      │
│     POST /v1/payments/orders                                      │
│     X-Api-Key: key_live_a1b2c3d4                                     │
│     X-Timestamp: 2024-01-15T10:30:00Z                                │
│     X-Signature: <base64(signature)>                                  │
│     Content-Type: application/json                                     │
│     Body: {"amount":50000,"currency":"INR"}                           │
│                                                                        │
│  ─────────────────────────────────────────────────────────────────    │
│                                                                        │
│  API GATEWAY VALIDATION:                                               │
│                                                                        │
│  1. Extract X-Api-Key → Look up in Redis (cached) or DB              │
│     → Get merchant_id, api_secret_hash, permissions, status          │
│                                                                        │
│  2. Check timestamp freshness:                                         │
│     if |now - timestamp| > 5 minutes → REJECT (replay attack)        │
│                                                                        │
│  3. Rebuild signature:                                                 │
│     string_to_sign = timestamp + "|" + method + "|" + path + "|"     │
│                      + SHA256(body)                                    │
│     expected_sig = HMAC-SHA256(string_to_sign, api_secret)           │
│                                                                        │
│  4. Compare signatures (constant-time comparison):                    │
│     if signature != expected_sig → REJECT (tampered request)         │
│                                                                        │
│  5. Check permissions:                                                 │
│     if !hasPermission(api_key, requested_endpoint) → 403             │
│                                                                        │
│  6. Check IP whitelist:                                                │
│     if ip_whitelist.notEmpty() && !ip_whitelist.contains(ip) → 403   │
│                                                                        │
│  7. ✅ VALID → Route to service with X-Merchant-Id header            │
│                                                                        │
└──────────────────────────────────────────────────────────────────────┘
```

### Rate Limiting — Token Bucket Algorithm

```
┌──────────────────────────────────────────────────────────────────────┐
│                    RATE LIMITING DESIGN                                │
├──────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  ALGORITHM: Token Bucket (implemented in Redis)                       │
│                                                                        │
│  CONCEPT:                                                              │
│  ┌──────────────────────────────────────┐                            │
│  │  Bucket Capacity: 100 tokens          │                            │
│  │  Refill Rate: 10 tokens/second        │                            │
│  │                                        │                            │
│  │  ┌─┬─┬─┬─┬─┬─┬─┬─┬─┬─┐             │                            │
│  │  │●│●│●│●│●│●│●│○│○│○│ = 7 tokens   │                            │
│  │  └─┴─┴─┴─┴─┴─┴─┴─┴─┴─┘             │                            │
│  │                                        │                            │
│  │  Each request consumes 1 token.        │                            │
│  │  If bucket empty → 429 Too Many Req   │                            │
│  │  Tokens refill at constant rate.       │                            │
│  └──────────────────────────────────────┘                            │
│                                                                        │
│  REDIS IMPLEMENTATION (Lua script for atomicity):                     │
│                                                                        │
│  ```lua                                                                │
│  -- Token Bucket Rate Limiter (Redis Lua Script)                      │
│  local key = KEYS[1]                                                  │
│  local capacity = tonumber(ARGV[1])     -- max tokens                 │
│  local refill_rate = tonumber(ARGV[2])  -- tokens per second          │
│  local now = tonumber(ARGV[3])          -- current timestamp (ms)     │
│  local requested = tonumber(ARGV[4])    -- tokens requested (1)       │
│                                                                        │
│  local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')    │
│  local tokens = tonumber(bucket[1]) or capacity                       │
│  local last_refill = tonumber(bucket[2]) or now                       │
│                                                                        │
│  -- Calculate tokens to add based on elapsed time                     │
│  local elapsed = (now - last_refill) / 1000                           │
│  local new_tokens = math.min(capacity, tokens + elapsed * refill_rate)│
│                                                                        │
│  local allowed = new_tokens >= requested                               │
│  if allowed then                                                       │
│    new_tokens = new_tokens - requested                                 │
│  end                                                                   │
│                                                                        │
│  redis.call('HMSET', key, 'tokens', new_tokens, 'last_refill', now)  │
│  redis.call('EXPIRE', key, capacity / refill_rate * 2)               │
│                                                                        │
│  return {allowed and 1 or 0, math.floor(new_tokens)}                 │
│  ```                                                                   │
│                                                                        │
│  RATE LIMIT TIERS:                                                     │
│  ┌────────────────────────────────────────────────────────────┐      │
│  │ Tier        │ Requests/sec │ Burst │ Monthly Limit         │      │
│  │─────────────┼──────────────┼───────┼──────────────────────│      │
│  │ Free        │     10       │  20   │    10,000             │      │
│  │ Standard    │     50       │  100  │   100,000             │      │
│  │ Premium     │    200       │  500  │ 1,000,000             │      │
│  │ Enterprise  │   1000       │ 2000  │ Unlimited             │      │
│  └────────────────────────────────────────────────────────────┘      │
│                                                                        │
│  RATE LIMIT HEADERS (returned on every response):                     │
│  X-RateLimit-Limit: 100                                               │
│  X-RateLimit-Remaining: 73                                            │
│  X-RateLimit-Reset: 1705313100 (Unix timestamp)                      │
│                                                                        │
│  ON 429 RESPONSE:                                                      │
│  Retry-After: 2 (seconds until tokens available)                      │
│                                                                        │
└──────────────────────────────────────────────────────────────────────┘
```

### Webhook HMAC Signing

```
┌──────────────────────────────────────────────────────────────────────┐
│                    WEBHOOK SECURITY (HMAC SIGNING)                     │
├──────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  WHY SIGN WEBHOOKS?                                                   │
│  - Merchants need to verify webhook came from PayFlow (not attacker) │
│  - Prevents forged webhook attacks (fake "payment.captured" events)  │
│  - Ensures payload wasn't tampered in transit                        │
│                                                                        │
│  ═══ PAYFLOW (SENDER) ═══════════════════════════════════════════    │
│                                                                        │
│  1. Prepare webhook payload:                                          │
│     payload = {                                                        │
│       "event": "payment.captured",                                    │
│       "payload": { "payment_id": "pay_x1y2z3", "amount": 50000 },   │
│       "timestamp": 1705312200,                                        │
│       "webhook_id": "wh_abc123"                                       │
│     }                                                                  │
│                                                                        │
│  2. Generate signature:                                                │
│     signature_string = timestamp + "." + JSON.stringify(payload)      │
│     signature = HMAC-SHA256(signature_string, webhook_secret)         │
│                                                                        │
│  3. Send webhook:                                                      │
│     POST https://merchant.com/webhooks/payflow                        │
│     Content-Type: application/json                                     │
│     X-PayFlow-Signature: t=1705312200,v1=<hex(signature)>            │
│     X-PayFlow-Webhook-Id: wh_abc123                                  │
│     Body: <payload>                                                    │
│                                                                        │
│  ═══ MERCHANT (RECEIVER) ═════════════════════════════════════════    │
│                                                                        │
│  1. Extract timestamp and signature from header:                      │
│     header = "t=1705312200,v1=a1b2c3d4..."                           │
│     timestamp = 1705312200                                            │
│     received_sig = "a1b2c3d4..."                                     │
│                                                                        │
│  2. Check timestamp freshness:                                         │
│     if |now - timestamp| > 5 minutes → REJECT (replay attack)        │
│                                                                        │
│  3. Recompute signature:                                               │
│     signature_string = timestamp + "." + raw_body                     │
│     expected_sig = HMAC-SHA256(signature_string, my_webhook_secret)   │
│                                                                        │
│  4. Compare (constant-time):                                           │
│     if received_sig != expected_sig → REJECT (forged/tampered)       │
│                                                                        │
│  5. ✅ Process webhook event                                          │
│                                                                        │
│  ═══ WEBHOOK RETRY POLICY ═════════════════════════════════════════   │
│                                                                        │
│  Attempt 1: Immediate                                                  │
│  Attempt 2: After 5 minutes                                           │
│  Attempt 3: After 30 minutes                                          │
│  Attempt 4: After 2 hours                                             │
│  Attempt 5: After 8 hours                                             │
│  Attempt 6: After 24 hours                                            │
│                                                                        │
│  After 6 failed attempts:                                              │
│  - Mark webhook as failing                                             │
│  - Send alert email to merchant                                       │
│  - After 100 consecutive failures: auto-disable webhook              │
│                                                                        │
│  Success criteria: HTTP 2xx within 30 seconds                         │
│                                                                        │
└──────────────────────────────────────────────────────────────────────┘
```

### PCI-DSS Compliance Essentials

```
┌──────────────────────────────────────────────────────────────────────┐
│                    PCI-DSS KEY REQUIREMENTS                            │
├──────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  REQUIREMENT 1: Secure Network                                        │
│  ├── Firewall between public internet and cardholder data            │
│  ├── No default passwords on any system component                    │
│  └── Network segmentation (CDE isolated)                             │
│                                                                        │
│  REQUIREMENT 3: Protect Stored Data                                   │
│  ├── NEVER store CVV/CVC (not even encrypted)                        │
│  ├── Mask PAN when displayed: 4111 **** **** 1111                    │
│  ├── Encrypt stored PAN with AES-256                                 │
│  ├── Key management: separate key custodians                         │
│  └── Retention policy: delete when no longer needed                  │
│                                                                        │
│  REQUIREMENT 4: Encrypt Transmission                                  │
│  ├── TLS 1.2+ for all cardholder data transmission                   │
│  ├── No sending PAN via email, chat, or unencrypted channels         │
│  └── Strong cryptography for all non-console admin access            │
│                                                                        │
│  REQUIREMENT 6: Secure Systems                                        │
│  ├── Security patches within 30 days                                 │
│  ├── Secure SDLC (code review, OWASP Top 10 testing)                │
│  ├── Change management for all system components                     │
│  └── Separation of dev/test/prod environments                        │
│                                                                        │
│  PAYFLOW'S APPROACH:                                                   │
│  ├── We tokenize card data (never store raw PAN in payment_db)       │
│  ├── Card details passed to bank and immediately discarded           │
│  ├── Only store: last4, network, issuer (for display)                │
│  ├── Full PAN exists only in memory during transaction               │
│  └── Bank Simulator accepts tokenized card references                │
│                                                                        │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 📊 Capacity Planning

### Back-of-Envelope Calculations

This section demonstrates how to estimate system requirements — a critical skill for system design interviews.

### Transaction Volume Estimates

```
┌──────────────────────────────────────────────────────────────────────┐
│                    TRAFFIC ESTIMATION                                  │
├──────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  ASSUMPTIONS:                                                          │
│  - Target: 1,000 merchants                                            │
│  - Average: 100 transactions/merchant/day                             │
│  - Peak: 3x average (festival/sale periods)                          │
│                                                                        │
│  DAILY VOLUME:                                                         │
│  1,000 merchants × 100 txns = 100,000 transactions/day              │
│                                                                        │
│  PEAK TPS CALCULATION:                                                 │
│  - Assumption: 80% of transactions happen in 8-hour business window  │
│  - Peak transactions: 100,000 × 0.8 = 80,000 in 8 hours            │
│  - Average TPS (during peak hours): 80,000 / (8 × 3600) = 2.8 TPS  │
│  - Peak factor: 10x average (real-world burst)                       │
│  - Peak TPS: 2.8 × 10 = ~28 TPS (normal operations)                │
│                                                                        │
│  SCALE TARGET (10x headroom):                                         │
│  - Design for: 1,000 TPS sustained                                   │
│  - Burst capacity: 2,000 TPS (30 seconds)                           │
│                                                                        │
│  WHY 1000 TPS TARGET?                                                 │
│  - 10x current need provides 2-3 years growth runway                 │
│  - Matches mid-size payment gateway (Razorpay was ~500 TPS in 2020) │
│  - Allows aggressive merchant acquisition without re-architecture    │
│                                                                        │
└──────────────────────────────────────────────────────────────────────┘
```

### Storage Estimates

```
┌──────────────────────────────────────────────────────────────────────┐
│                    STORAGE ESTIMATION                                  │
├──────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  ═══ PAYMENT DATABASE ══════════════════════════════════════════      │
│                                                                        │
│  Orders table:                                                         │
│  - Row size: ~500 bytes (UUID + amounts + status + JSONB + timestamps)│
│  - 100,000 orders/day × 500 bytes = 50 MB/day                       │
│  - Per year: 50 MB × 365 = 18.25 GB/year                            │
│  - With indexes (2x): ~37 GB/year                                    │
│                                                                        │
│  Payments table:                                                       │
│  - Row size: ~1 KB (more columns than orders)                        │
│  - ~120,000 attempts/day (1.2 attempts per order average)            │
│  - 120,000 × 1 KB = 120 MB/day                                      │
│  - Per year: 120 MB × 365 = 43.8 GB/year                            │
│  - With indexes (2x): ~88 GB/year                                    │
│                                                                        │
│  Payment Events table:                                                 │
│  - Row size: ~300 bytes                                               │
│  - ~3 events per payment (created → authorized → captured)           │
│  - 360,000 events/day × 300 bytes = 108 MB/day                      │
│  - Per year: ~40 GB/year (with indexes: ~60 GB/year)                │
│                                                                        │
│  Idempotency Keys table:                                              │
│  - Row size: ~2 KB (includes cached response)                        │
│  - 100,000 keys/day, 24-hour retention                               │
│  - Max stored: ~200 MB (rolling 24-hour window)                      │
│                                                                        │
│  TOTAL PAYMENT DB: ~190 GB/year                                       │
│  With 3-year retention + replication: ~1.2 TB                        │
│                                                                        │
│  ═══ REDIS ════════════════════════════════════════════════════       │
│                                                                        │
│  Idempotency Keys (hot):                                              │
│  - 100,000 keys × 2 KB = 200 MB                                     │
│  - TTL: 24 hours (auto-evict)                                        │
│                                                                        │
│  Rate Limit Counters:                                                  │
│  - 1,000 merchants × ~100 bytes = 100 KB                            │
│  - Per-IP counters: ~50,000 × 50 bytes = 2.5 MB                     │
│                                                                        │
│  Merchant Config Cache:                                                │
│  - 1,000 merchants × 2 KB = 2 MB                                    │
│  - TTL: 5 minutes (invalidate on update)                             │
│                                                                        │
│  API Key Cache:                                                        │
│  - 5,000 keys × 500 bytes = 2.5 MB                                  │
│  - TTL: 1 hour                                                        │
│                                                                        │
│  Session/Refresh Tokens:                                               │
│  - 10,000 active sessions × 200 bytes = 2 MB                        │
│                                                                        │
│  TOTAL REDIS: ~210 MB (well within single node)                      │
│  Recommendation: 2 GB allocation (10x headroom)                      │
│                                                                        │
│  ═══ KAFKA ════════════════════════════════════════════════════       │
│                                                                        │
│  Message size (average): 1 KB (JSON event)                           │
│  Messages per transaction: 3 (created, authorized, captured)         │
│  Daily messages: 100,000 × 3 = 300,000 messages                     │
│  Daily storage: 300,000 × 1 KB = 300 MB                             │
│                                                                        │
│  With replication factor 3: 900 MB/day                               │
│  With 7-day retention: 6.3 GB                                        │
│  With 30-day retention (settlement topics): +9 GB                    │
│                                                                        │
│  TOTAL KAFKA: ~20 GB (comfortable on 3-node cluster)                │
│                                                                        │
└──────────────────────────────────────────────────────────────────────┘
```

### Compute Estimates

```
┌──────────────────────────────────────────────────────────────────────┐
│                    COMPUTE / INSTANCE SIZING                           │
├──────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  Service Instances (for 1000 TPS):                                    │
│                                                                        │
│  ┌──────────────────────┬──────────┬────────┬──────────────────┐     │
│  │ Service              │ CPU      │ Memory │ Instances (min)  │     │
│  ├──────────────────────┼──────────┼────────┼──────────────────┤     │
│  │ API Gateway          │ 2 cores  │ 2 GB   │ 3 (HA)          │     │
│  │ Identity Service     │ 2 cores  │ 2 GB   │ 2               │     │
│  │ Payment Service      │ 4 cores  │ 4 GB   │ 4 (high traffic) │    │
│  │ Merchant Service     │ 2 cores  │ 2 GB   │ 2               │     │
│  │ Settlement Service   │ 2 cores  │ 4 GB   │ 2               │     │
│  │ Notification Service │ 2 cores  │ 2 GB   │ 2               │     │
│  │ Fraud Engine         │ 2 cores  │ 4 GB   │ 2               │     │
│  │ Payment Router       │ 2 cores  │ 2 GB   │ 3 (critical)    │     │
│  │ Bank Simulator       │ 2 cores  │ 2 GB   │ 2               │     │
│  ├──────────────────────┼──────────┼────────┼──────────────────┤     │
│  │ TOTAL                │ 22 cores │ 24 GB  │ 22 instances     │     │
│  └──────────────────────┴──────────┴────────┴──────────────────┘     │
│                                                                        │
│  Infrastructure:                                                       │
│  ┌──────────────────────┬──────────┬────────┬──────────────────┐     │
│  │ Component            │ CPU      │ Memory │ Instances        │     │
│  ├──────────────────────┼──────────┼────────┼──────────────────┤     │
│  │ PostgreSQL Primary   │ 4 cores  │ 16 GB  │ 4 (per-service)  │    │
│  │ PostgreSQL Replica   │ 4 cores  │ 16 GB  │ 4 (read replicas)│    │
│  │ Redis Cluster        │ 2 cores  │ 4 GB   │ 6 (3P + 3R)     │     │
│  │ Kafka Brokers        │ 4 cores  │ 8 GB   │ 3               │     │
│  │ Kafka (KRaft)        │ 2 cores  │ 2 GB   │ 3               │     │
│  ├──────────────────────┼──────────┼────────┼──────────────────┤     │
│  │ TOTAL                │ 48 cores │ 138 GB │ 20 instances     │     │
│  └──────────────────────┴──────────┴────────┴──────────────────┘     │
│                                                                        │
│  GRAND TOTAL: ~70 cores, ~162 GB RAM, 42 instances                   │
│  Cloud cost estimate: ~$3,000-5,000/month (AWS/GCP)                  │
│                                                                        │
│  SCALING TRIGGERS:                                                     │
│  - CPU > 70% sustained for 5 min → scale out                        │
│  - Memory > 80% → scale up                                           │
│  - p99 latency > 800ms → investigate + scale                        │
│  - Queue depth > 10,000 messages → add consumers                    │
│                                                                        │
└──────────────────────────────────────────────────────────────────────┘
```

### Network Bandwidth Estimates

```
┌──────────────────────────────────────────────────────────────────────┐
│                    NETWORK BANDWIDTH                                   │
├──────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  Inbound (from merchants):                                            │
│  - Request size (avg): 2 KB                                          │
│  - At 1000 TPS: 2 KB × 1000 = 2 MB/sec                             │
│                                                                        │
│  Outbound (to merchants):                                             │
│  - Response size (avg): 1.5 KB                                       │
│  - At 1000 TPS: 1.5 KB × 1000 = 1.5 MB/sec                         │
│                                                                        │
│  Inter-service:                                                        │
│  - Internal calls per request: ~3 (avg)                              │
│  - Internal message size: 1 KB (avg)                                 │
│  - Internal bandwidth: 3 KB × 1000 TPS = 3 MB/sec                   │
│                                                                        │
│  Kafka:                                                                │
│  - Messages/sec: 3000 (3 per transaction)                            │
│  - Message size: 1 KB                                                │
│  - With replication (3x): 9 MB/sec                                   │
│                                                                        │
│  Total bandwidth: ~16 MB/sec = ~128 Mbps                             │
│  Recommendation: 1 Gbps network (8x headroom)                       │
│                                                                        │
└──────────────────────────────────────────────────────────────────────┘
```

### Connection Pool Sizing

```
┌──────────────────────────────────────────────────────────────────────┐
│                    CONNECTION POOL MATH                                │
├──────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  DATABASE CONNECTION POOL (HikariCP):                                 │
│                                                                        │
│  Formula: pool_size = (core_count × 2) + effective_spindle_count     │
│                                                                        │
│  For our setup (4 cores, SSD):                                        │
│  pool_size = (4 × 2) + 1 = 9 (per instance)                         │
│  Rounded to: 10 connections per instance                              │
│                                                                        │
│  Payment Service (4 instances × 10 connections) = 40 connections     │
│  PostgreSQL max_connections = 100 (default)                           │
│  Usage: 40/100 = 40% (healthy — leave room for admin + monitoring)   │
│                                                                        │
│  REDIS CONNECTION POOL:                                                │
│  - Pool size per instance: 20 connections                             │
│  - Total: 22 instances × 20 = 440 connections to Redis              │
│  - Redis can handle 10,000+ concurrent connections (fine)            │
│                                                                        │
│  KAFKA CONNECTIONS:                                                    │
│  - 1 producer connection per service instance                        │
│  - Consumer connections = number of partitions consumed              │
│  - Total: ~100 connections (well within Kafka limits)                │
│                                                                        │
│  BANK TCP CONNECTIONS:                                                 │
│  - Pool size: 50 persistent connections per acquirer                 │
│  - At 1000 TPS with 200ms avg response: need 200 concurrent         │
│  - 50 connections × 4 router instances = 200 (exactly right)        │
│  - Headroom: increase to 75 per instance for bursts                  │
│                                                                        │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 🧪 Reliability Patterns Deep Dive

### Retry with Exponential Backoff — Implementation

```
Delay calculation for each retry attempt:

  Attempt 1: delay = min(30s, 1s × 2^1) + jitter = 2s + random(0, 2s)
  Attempt 2: delay = min(30s, 1s × 2^2) + jitter = 4s + random(0, 4s)
  Attempt 3: delay = min(30s, 1s × 2^3) + jitter = 8s + random(0, 8s)

  Parameters:
  - base_delay: 1 second
  - multiplier: 2 (exponential)
  - max_delay: 30 seconds (cap)
  - max_retries: 3
  - jitter: full (random between 0 and calculated delay)

  Total worst-case time: 2 + 4 + 8 = 14 seconds (without jitter)
  Total with max jitter: 4 + 8 + 16 = 28 seconds
```

### Circuit Breaker — State Machine

```
┌──────────────────────────────────────────────────────────────────────┐
│                    CIRCUIT BREAKER STATE MACHINE                       │
├──────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  ┌────────────┐    failure_rate > 50%     ┌──────────┐              │
│  │   CLOSED   │─────────────────────────►│   OPEN   │              │
│  │ (normal)   │                           │ (fail    │              │
│  │            │                           │  fast)   │              │
│  └─────┬──────┘                           └────┬─────┘              │
│        │  ▲                                    │                      │
│        │  │  success_rate > 80%                │ wait_duration        │
│        │  │  (in half-open)                    │ (60 seconds)         │
│        │  │                                    │                      │
│        │  │     ┌─────────────┐               │                      │
│        │  └─────│  HALF-OPEN  │◄──────────────┘                      │
│        │        │ (probe)     │                                       │
│        │        └──────┬──────┘                                       │
│        │               │                                              │
│        │               │ failure in probe                             │
│        │               │                                              │
│        │               ▼                                              │
│        │        Back to OPEN                                          │
│        │                                                              │
│  ─────────────────────────────────────────────────────────────────   │
│                                                                        │
│  CONFIGURATION:                                                        │
│  - Sliding window size: 100 requests                                  │
│  - Failure rate threshold: 50%                                        │
│  - Wait duration in OPEN: 60 seconds                                 │
│  - Permitted calls in HALF-OPEN: 10                                  │
│  - Success threshold in HALF-OPEN: 80%                               │
│                                                                        │
│  WHAT COUNTS AS FAILURE:                                               │
│  ✅ Timeout (bank didn't respond in 10s)                             │
│  ✅ 5xx response from bank                                           │
│  ✅ Connection refused / reset                                        │
│  ❌ Business decline (response code 51: insufficient funds)          │
│  ❌ Validation errors (our problem, not bank's)                      │
│                                                                        │
│  IMPORTANT: A business decline (card declined) is NOT a circuit      │
│  breaker failure! Only infrastructure failures count.                 │
│                                                                        │
└──────────────────────────────────────────────────────────────────────┘
```

### Idempotency — Complete Flow

```
┌──────────────────────────────────────────────────────────────────────┐
│                    IDEMPOTENCY COMPLETE FLOW                           │
├──────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  Request arrives with Idempotency-Key: "abc-123"                     │
│                                                                        │
│  ┌─────────────────────────────────────────────────────┐            │
│  │ Step 1: Check Redis                                   │            │
│  │         GET idempotency:merchant_x:abc-123            │            │
│  └────────────────────────┬────────────────┬─────────────┘           │
│                           │                │                          │
│              Key NOT found │                │ Key found               │
│                           ▼                ▼                          │
│  ┌──────────────────────────┐  ┌──────────────────────────┐        │
│  │ Step 2a: Check DB backup │  │ Step 2b: Return cached   │        │
│  │ SELECT FROM              │  │ response immediately      │        │
│  │ idempotency_keys         │  │ (HTTP 200 with original  │        │
│  │ WHERE key = 'abc-123'    │  │  response body)           │        │
│  └───────────┬──────────────┘  └──────────────────────────┘        │
│              │                                                        │
│     Not in DB either                                                  │
│              │                                                        │
│              ▼                                                        │
│  ┌──────────────────────────────────────────────────────┐           │
│  │ Step 3: Set processing lock                           │           │
│  │         SETNX idempotency:merchant_x:abc-123          │           │
│  │               {"status": "PROCESSING"}                │           │
│  │               EX 300 (5 min timeout)                  │           │
│  └───────────────────────┬──────────────────────────────┘           │
│                          │                                            │
│                          ▼                                            │
│  ┌──────────────────────────────────────────────────────┐           │
│  │ Step 4: Process the actual request                    │           │
│  │         (create order, authorize payment, etc.)       │           │
│  └───────────────────────┬──────────────────────────────┘           │
│                          │                                            │
│                          ▼                                            │
│  ┌──────────────────────────────────────────────────────┐           │
│  │ Step 5: Store result                                  │           │
│  │         Redis: SET with response + 24hr TTL           │           │
│  │         DB: INSERT into idempotency_keys              │           │
│  └───────────────────────┬──────────────────────────────┘           │
│                          │                                            │
│                          ▼                                            │
│  ┌──────────────────────────────────────────────────────┐           │
│  │ Step 6: Return response to merchant                   │           │
│  └──────────────────────────────────────────────────────┘           │
│                                                                        │
│  EDGE CASE: Same key, different body                                  │
│  → Compare request_hash                                               │
│  → If mismatch: return 409 Conflict                                  │
│    "Idempotency key already used with different parameters"          │
│                                                                        │
│  EDGE CASE: Key exists but status = PROCESSING                       │
│  → Another request is in-flight with same key                        │
│  → Return 409 with "Request already in progress"                     │
│  → Client should retry after brief delay                             │
│                                                                        │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 📝 What You Learned

### Concepts Summary Table

| # | Concept | Key Takeaway | Real-World Application |
|---|---------|-------------|----------------------|
| 1 | **Microservices** | Bounded contexts with independent scaling | Stripe's 500+ services |
| 2 | **Database per Service** | Data ownership prevents coupling | Amazon's API mandate |
| 3 | **ACID vs BASE** | Financial data needs ACID; analytics can be BASE | PostgreSQL for payments, Redis for caching |
| 4 | **UUID v7** | Time-ordered globally unique IDs without coordination | Stripe's prefixed IDs |
| 5 | **Event-Driven Architecture** | Kafka enables async, decoupled communication | All modern payment systems |
| 6 | **Idempotency** | Prevent duplicate operations in distributed systems | Every payment API must be idempotent |
| 7 | **Circuit Breaker** | Fail fast when downstream is unhealthy | Netflix/Stripe resilience patterns |
| 8 | **Exponential Backoff** | Prevent thundering herd on retry | AWS SDK retry strategy |
| 9 | **ISO 8583** | Binary protocol for bank communication | Visa processes 65K TPS with it |
| 10 | **JWT + API Keys** | Stateless auth for users, HMAC for servers | Razorpay's dual-auth model |
| 11 | **Token Bucket** | Rate limiting with burst support | Redis-based implementation |
| 12 | **HMAC Signing** | Request integrity and webhook authenticity | Stripe's webhook signatures |
| 13 | **Batch Settlement** | How money actually moves in banking | T+2 settlement standard |
| 14 | **Capacity Planning** | Back-of-envelope math for system sizing | Critical for interviews |
| 15 | **Event Sourcing** | Immutable event log for audit and replay | Payment event history |

### Interview-Ready One-Liners

| Topic | One-Liner |
|-------|-----------|
| Why Microservices? | "Payment processing and settlement have different scaling characteristics and failure modes." |
| Why PostgreSQL? | "ACID compliance is non-negotiable when handling money. Eventual consistency means potential double-charging." |
| Why Kafka? | "Replayability is critical — if a consumer crashes, we replay the event log to recover state without data loss." |
| Why UUID v7? | "Global uniqueness for distributed writes while maintaining B-tree insert efficiency through time-ordering." |
| Why Circuit Breaker? | "When Bank A fails at 50%, we route to Bank B within milliseconds instead of waiting for timeouts." |
| Why Idempotency? | "The difference between a payment gateway and a payment disaster — never double-charge on retry." |
| Why HMAC? | "Even if intercepted, the request can't be modified — the signature would break." |
| Why Batch Settlement? | "Banks settle in batch cycles — we optimize by parallelizing merchant-level batches." |
| Why Redis? | "Sub-millisecond cache for configs AND atomic counters for rate limiting — dual-purpose." |
| Why Netty? | "Non-blocking TCP for ISO 8583 binary encoding with custom codecs — exactly what payment processing needs." |

### What's Next?

In **Phase 2: High-Level Design**, we will:
1. Create detailed component interaction diagrams
2. Define service communication patterns (sync vs async)
3. Design the deployment architecture (Kubernetes)
4. Set up observability (metrics, logging, tracing)
5. Define SLOs, SLIs, and error budgets

---

## 📚 Document Index

| Phase | Document | Description | Status |
|-------|----------|-------------|--------|
| 0.1 | [phase0-part1-foundation.md](./phase0-part1-foundation.md) | Project vision, goals, learning roadmap | ✅ Complete |
| 0.2 | [phase0-part2-environment-setup.md](./phase0-part2-environment-setup.md) | Dev environment, tools, Docker setup | ✅ Complete |
| **1** | **phase1-system-design.md** | **System design, decisions, API/DB design** | **✅ Current** |
| 2 | [phase2-high-level-design.md](./phase2-high-level-design.md) | Component interactions, deployment | 🔄 Next |
| 3 | phase3-implementation.md | Service implementation details | 📋 Planned |
| 4 | phase4-testing.md | Testing strategy, load testing | 📋 Planned |
| 5 | phase5-deployment.md | CI/CD, Kubernetes, monitoring | 📋 Planned |

---

## 📎 References & Further Reading

| Topic | Resource | Why Read It |
|-------|----------|-------------|
| ISO 8583 | ISO 8583 Wikipedia + Spec | Protocol deep-dive |
| System Design | "Designing Data-Intensive Applications" — Kleppmann | The bible for distributed systems |
| Payment Systems | "Payment Systems in India" — RBI | Understand Indian payment landscape |
| Kafka | "Kafka: The Definitive Guide" — Confluent | Event streaming mastery |
| PostgreSQL | "PostgreSQL 15 Internals" — Suzuki | MVCC, WAL, query planner |
| Resilience | "Release It!" — Michael Nygard | Circuit breakers, bulkheads, timeouts |
| API Design | "API Design Patterns" — JJ Geewax | REST best practices |
| Security | PCI-DSS v4.0 Quick Reference Guide | Compliance requirements |

---

> **"The best system design is one where each decision has a clear reason, each trade-off is acknowledged, and each component can evolve independently."**

---

*End of Phase 1: System Design*
*Next: [Phase 2: High-Level Design →](./phase2-high-level-design.md)*
