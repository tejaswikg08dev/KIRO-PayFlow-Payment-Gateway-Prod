# 🎯 System Design Interview Cheatsheet

> **"A quick reference card for your next system design interview — with PayFlow examples."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | Reference — Interview Prep |
| **Previous** | [system-design-complete-guide.md](./system-design-complete-guide.md) |
| **Next** | [phase4-part01-parent-pom-and-maven-setup.md](./phase4-part01-parent-pom-and-maven-setup.md) |
| **Author** | Tejaswi |
| **Created** | 2024 |
| **Status** | Living Document |

---

## 📖 Table of Contents

1. [45-Minute Interview Template](#45-minute-interview-template)
2. [SQL vs NoSQL Decision Tree](#sql-vs-nosql-decision-tree)
3. [Sync vs Async Decision Matrix](#sync-vs-async-decision-matrix)
4. [Estimation Formulas](#estimation-formulas)
5. [Common Mistakes to Avoid](#common-mistakes-to-avoid)
6. [PayFlow One-Page Architecture](#payflow-one-page-architecture)
7. [Quick Reference Tables](#quick-reference-tables)
8. [What You Learned](#what-you-learned)
9. [Document Index](#document-index)

---

## ⏱️ 45-Minute Interview Template

```
┌──────────────────────────────────────────────────────────────────┐
│           SYSTEM DESIGN INTERVIEW: 45-MIN BREAKDOWN              │
├──────────────────────────────────────────────────────────────────┤
│                                                                    │
│  [0-5 min] REQUIREMENTS GATHERING                                 │
│  ─────────────────────────────────                                │
│  • What are the core features? (Functional Requirements)          │
│  • How many users/TPS? (Scale)                                    │
│  • Latency requirements? (Performance)                            │
│  • Consistency vs Availability trade-off?                         │
│  • Any specific constraints? (Budget, region, compliance)         │
│                                                                    │
│  [5-10 min] BACK-OF-ENVELOPE ESTIMATION                          │
│  ────────────────────────────────────                              │
│  • DAU, QPS (peak = 3x average)                                  │
│  • Storage: per-record size × records/day × retention             │
│  • Bandwidth: QPS × avg response size                            │
│  • Memory (cache): 20% of hot data fits in RAM                   │
│                                                                    │
│  [10-25 min] HIGH-LEVEL DESIGN                                   │
│  ────────────────────────────                                      │
│  • Draw main components (boxes + arrows)                         │
│  • Define APIs (endpoints, request/response)                      │
│  • Choose database(s)                                             │
│  • Show data flow for main use case                              │
│                                                                    │
│  [25-40 min] DEEP DIVE                                           │
│  ──────────────────────                                            │
│  • Scale bottlenecks + solutions                                 │
│  • Database schema design                                         │
│  • Caching strategy                                               │
│  • Handle failures + edge cases                                   │
│  • Security considerations                                        │
│                                                                    │
│  [40-45 min] WRAP-UP                                             │
│  ───────────────────                                               │
│  • Summarize trade-offs made                                     │
│  • Future improvements / what you'd do with more time            │
│  • Monitoring & observability strategy                            │
│                                                                    │
└──────────────────────────────────────────────────────────────────┘
```

---

## 🌳 SQL vs NoSQL Decision Tree

```
START: What kind of data are you storing?
│
├─── Structured with relationships?
│    │
│    ├─── Need ACID transactions? ──── YES ───► PostgreSQL / MySQL
│    │                                              (PayFlow: payments, orders)
│    │
│    └─── Read-heavy analytics? ───── YES ───► Read Replicas + PostgreSQL
│                                                (PayFlow: dashboard queries)
│
├─── Key-Value pairs?
│    │
│    ├─── Need TTL / expiration? ──── YES ───► Redis
│    │                                           (PayFlow: rate limits, cache)
│    │
│    └─── Need persistence + scale?── YES ───► DynamoDB
│                                                (PayFlow: webhook delivery logs)
│
├─── Document (JSON blobs)?
│    │
│    ├─── Flexible schema needed? ──── YES ──► MongoDB / DynamoDB
│    │                                           (PayFlow: audit logs)
│    │
│    └─── Need full-text search? ──── YES ──► Elasticsearch
│                                               (PayFlow: transaction search)
│
├─── Time-series data?
│    │
│    └─── Metrics / monitoring? ───── YES ──► TimescaleDB / InfluxDB
│                                               (PayFlow: performance metrics)
│
└─── Event log / streaming?
     │
     └─── Ordered, durable events? ── YES ──► Apache Kafka
                                               (PayFlow: payment events)
```

### Quick Decision Table

| Question | If YES | If NO |
|----------|--------|-------|
| Need JOINs? | SQL | NoSQL okay |
| Need transactions? | SQL | NoSQL okay |
| Schema changes often? | NoSQL | SQL fine |
| Need >100K writes/sec? | NoSQL (DynamoDB) | SQL fine |
| Data is relational? | SQL | NoSQL better |
| Need auto-TTL cleanup? | Redis/DynamoDB | Manual cleanup |
| Need event replay? | Kafka | Regular queue |

---

## ⚖️ Sync vs Async Decision Matrix

```
┌────────────────────────────────────────────────────────────────────┐
│                SYNC vs ASYNC DECISION MATRIX                        │
├────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Use SYNCHRONOUS when:                                              │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │ • User needs immediate response (payment authorization)      │  │
│  │ • Operation is fast (<500ms)                                  │  │
│  │ • Failure must be immediately communicated                    │  │
│  │ • Strong consistency required                                 │  │
│  │ • Request-response pattern (REST API call)                   │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  PayFlow SYNC examples:                                             │
│  • POST /payments/authorize → must return auth code immediately     │
│  • GET /orders/{id} → must return current status                    │
│  • POST /auth/login → must return JWT immediately                   │
│                                                                      │
│  Use ASYNCHRONOUS when:                                             │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │ • Operation is slow (sending email, calling external API)    │  │
│  │ • User doesn't need to wait for result                       │  │
│  │ • Multiple independent consumers need the data               │  │
│  │ • Retry/reliability more important than speed                │  │
│  │ • Decoupling producers from consumers                        │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  PayFlow ASYNC examples:                                            │
│  • Webhook delivery (merchant endpoint might be slow)               │
│  • Email/SMS notifications (not user-blocking)                      │
│  • Settlement calculation (batch, no user waiting)                  │
│  • Analytics event publishing (fire-and-forget)                     │
│                                                                      │
│  HYBRID Pattern (PayFlow Payment Flow):                             │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │ 1. Create payment → SYNC response to merchant               │  │
│  │ 2. Publish event → ASYNC to Kafka                            │  │
│  │ 3. Webhook delivery → ASYNC (merchant gets notified later)  │  │
│  │ 4. Email to customer → ASYNC (arrives in seconds)           │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
└────────────────────────────────────────────────────────────────────┘
```

---

## 🔢 Estimation Formulas

### Common Estimation Cheat Sheet

```
┌─────────────────────────────────────────────────────────────────┐
│                    ESTIMATION FORMULAS                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  QPS (Queries Per Second):                                       │
│  ─────────────────────────                                        │
│  Average QPS = DAU × queries_per_user / 86400                    │
│  Peak QPS = Average QPS × 3 (rule of thumb)                      │
│                                                                   │
│  PayFlow Example:                                                │
│  • 10,000 merchants, each processing 100 txns/day               │
│  • Total: 1,000,000 txns/day                                    │
│  • Average QPS: 1,000,000 / 86,400 ≈ 12 TPS                    │
│  • Peak QPS: 12 × 3 ≈ 36 TPS                                   │
│  • Design for: 100 TPS (3x peak for growth)                     │
│                                                                   │
│  Storage:                                                         │
│  ────────                                                         │
│  Daily storage = QPS × 86400 × avg_record_size                   │
│                                                                   │
│  PayFlow Example:                                                │
│  • 1M transactions/day                                           │
│  • Average record: 500 bytes (order + payment + metadata)        │
│  • Daily: 1M × 500B = 500MB/day                                 │
│  • Yearly: 500MB × 365 = 182GB/year                             │
│  • 5 years: ~1TB (fits single PostgreSQL easily)                 │
│                                                                   │
│  Memory (Cache):                                                  │
│  ──────────────                                                   │
│  Cache size = hot_data_percentage × total_data                   │
│  Rule: 20% of data serves 80% of reads (Pareto)                 │
│                                                                   │
│  PayFlow Example:                                                │
│  • 10,000 merchants, need fast API key lookup                   │
│  • Each cache entry: 200 bytes (key hash + merchant ID)          │
│  • Total: 10,000 × 200B = 2MB (trivial for Redis)              │
│                                                                   │
│  Bandwidth:                                                       │
│  ──────────                                                       │
│  Bandwidth = QPS × avg_response_size                              │
│                                                                   │
│  PayFlow Example:                                                │
│  • 100 QPS × 2KB avg response = 200KB/s = 1.6 Mbps             │
│  • Very manageable for any cloud provider                        │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  QUICK REFERENCE NUMBERS                                     ││
│  ├─────────────────────────────────────────────────────────────┤│
│  │  1 day = 86,400 seconds                                     ││
│  │  1 year ≈ 31.5 million seconds                              ││
│  │  1 million requests/day ≈ 12 QPS                            ││
│  │  1 billion requests/day ≈ 12,000 QPS                        ││
│  │  1 KB × 1 million = 1 GB                                    ││
│  │  1 KB × 1 billion = 1 TB                                    ││
│  │  Redis: ~100K ops/sec single thread                         ││
│  │  PostgreSQL: ~10K TPS (properly configured)                 ││
│  │  Kafka: ~100K messages/sec per partition                    ││
│  │  Network round trip (same region): ~1ms                     ││
│  │  Disk seek: ~10ms (HDD), ~0.1ms (SSD)                     ││
│  │  Memory read: ~100ns                                        ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## ❌ Common Mistakes to Avoid

```
┌─────────────────────────────────────────────────────────────────┐
│                COMMON INTERVIEW MISTAKES                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Mistake 1: Jumping into solution without requirements           │
│  ──────────────────────────────────────────────────────          │
│  ✗ "Let's use Kafka and microservices!"                         │
│  ✓ "What's the expected scale? Do we need real-time?"           │
│                                                                   │
│  Mistake 2: Over-engineering for scale you don't need            │
│  ──────────────────────────────────────────────────────          │
│  ✗ "We need 50 microservices and Kubernetes from day 1"         │
│  ✓ "For 100 TPS, a well-designed monolith works. Here's how   │
│     we'd split it when we hit 10K TPS..."                       │
│                                                                   │
│  Mistake 3: Ignoring failure scenarios                           │
│  ──────────────────────────────────────                          │
│  ✗ Draw happy path only                                         │
│  ✓ "What happens if the bank times out? Circuit breaker opens, │
│     we retry on backup acquirer..."                              │
│                                                                   │
│  Mistake 4: Not considering data consistency                     │
│  ─────────────────────────────────────────                       │
│  ✗ "We'll just use eventually consistent everywhere"            │
│  ✓ "Payments need strong consistency (no double-charge),        │
│     but webhooks can be eventually consistent"                   │
│                                                                   │
│  Mistake 5: Forgetting about security                            │
│  ─────────────────────────────────────                           │
│  ✗ No mention of auth, encryption, or PCI                       │
│  ✓ "Card numbers never stored in full. API keys hashed.        │
│     All inter-service calls over TLS."                           │
│                                                                   │
│  Mistake 6: Single point of failure                              │
│  ──────────────────────────────────                              │
│  ✗ One database, one server, one everything                     │
│  ✓ "DB has replication, services have multiple instances,       │
│     Kafka has replication factor 3"                               │
│                                                                   │
│  Mistake 7: Not discussing trade-offs                            │
│  ─────────────────────────────────────                           │
│  ✗ "This is the perfect solution"                               │
│  ✓ "I chose Kafka over RabbitMQ because we need message        │
│     replay for debugging, but trade-off is more complexity"     │
│                                                                   │
│  Mistake 8: Monologue instead of dialogue                       │
│  ─────────────────────────────────────────                       │
│  ✗ Talk for 30 minutes without checking with interviewer        │
│  ✓ "Does this level of detail make sense, or should I          │
│     dive deeper into the payment flow?"                          │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🏗️ PayFlow One-Page Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                 PAYFLOW PAYMENT GATEWAY — ONE-PAGE ARCHITECTURE              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  WHAT: Production-grade payment gateway processing cards, UPI, net banking   │
│  SCALE: 1000 TPS peak, 99.9% uptime, p99 < 1s latency                      │
│  TECH: Spring Boot 3.2, Spring Cloud, PostgreSQL, Redis, Kafka, Netty       │
│                                                                               │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                                                                       │   │
│  │  [React+TS Dashboard] ──HTTPS──► [API Gateway :8080]                 │   │
│  │  [Hosted Checkout]     ──HTTPS──►    │ JWT + API Key + Rate Limit    │   │
│  │  [Merchant Server]     ──HTTPS──►    │                               │   │
│  │                                       │                               │   │
│  │                    ┌──────────────────┼──────────────────────┐       │   │
│  │                    │                  │                       │       │   │
│  │              [Identity:8081]  [Payment:8083]  [Merchant:8082] │       │   │
│  │              │ JWT + BCrypt    │ Orders/Pay    │ API Keys     │       │   │
│  │              │ PostgreSQL      │ PostgreSQL    │ PostgreSQL   │       │   │
│  │                                │                              │       │   │
│  │                          [Routing:8084]                       │       │   │
│  │                          │ ISO 8583 + Netty                   │       │   │
│  │                          │ Fraud + Smart Routing              │       │   │
│  │                          │ Resilience4j Circuit Breaker       │       │   │
│  │                          │                                    │       │   │
│  │                    [Bank Simulator:9090] ← TCP                │       │   │
│  │                                                               │       │   │
│  │  ─────────────── KAFKA EVENT BUS ────────────────────        │       │   │
│  │                    │           │           │                   │       │   │
│  │              [Webhook:8086]  [Notify:8087]  [Settle:8085]    │       │   │
│  │              │ HMAC+Retry    │ Email/SMS    │ Spring Batch    │       │   │
│  │              │ DynamoDB      │ Templates    │ Fee Calc        │       │   │
│  │                                                               │       │   │
│  │  ─────────────── INFRASTRUCTURE ─────────────────────        │       │   │
│  │  [Eureka:8761] [Config:8888] [Redis:6379] [Kafka:9092]      │       │   │
│  │                                                               │       │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                               │
│  KEY DECISIONS:                                                               │
│  • PostgreSQL for ACID payments (not MongoDB — money needs transactions)    │
│  • Kafka over RabbitMQ (need replay for debugging + ordering per merchant)  │
│  • JWT over sessions (stateless services, horizontal scaling)                │
│  • Netty over REST for bank (ISO 8583 is binary TCP protocol)               │
│  • Redis for idempotency (fast SET NX with TTL, not DB lock)               │
│  • Circuit breaker (bank can go down, shouldn't cascade to all services)    │
│                                                                               │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 📊 Quick Reference Tables

### HTTP Status Codes for Payment APIs

| Status | Meaning | PayFlow Use Case |
|--------|---------|------------------|
| 200 | Success | Payment captured, order fetched |
| 201 | Created | Order created, user registered |
| 204 | No Content | Logout, delete API key |
| 400 | Bad Request | Invalid amount, missing fields |
| 401 | Unauthorized | Invalid JWT, wrong API key |
| 403 | Forbidden | MERCHANT trying admin endpoint |
| 404 | Not Found | Order ID doesn't exist |
| 409 | Conflict | Duplicate email, idempotency hit |
| 422 | Unprocessable | Capture expired auth, refund > captured |
| 429 | Too Many Requests | Rate limit exceeded |
| 500 | Internal Error | Unexpected exception |
| 502 | Bad Gateway | Bank returned invalid response |
| 504 | Gateway Timeout | Bank didn't respond in 30s |

### CAP Theorem Applied

| Service | Prioritizes | Sacrifice | Reason |
|---------|------------|-----------|--------|
| Payment Service | Consistency + Partition tolerance | Availability (brief) | Cannot double-charge |
| Webhook Service | Availability + Partition tolerance | Consistency | Eventual delivery is fine |
| Dashboard Reads | Availability + Partition tolerance | Consistency | Stale data for seconds okay |
| Settlement Batch | Consistency + Availability | — (no partition issue, internal) | Calculations must be exact |

### Technology Selection Rationale

| Technology | Why Chosen | Alternative Considered | Why Not Alternative |
|-----------|-----------|----------------------|-------------------|
| Spring Boot 3.2 | Java ecosystem, enterprise support | Node.js | JVM better for CPU-heavy crypto |
| PostgreSQL | ACID, JSON support, mature | MySQL | Better JSON, better extensions |
| Redis | Sub-ms latency, TTL support | Memcached | Redis has persistence + data structures |
| Kafka | Ordering, replay, high throughput | RabbitMQ | Need event replay for debugging |
| Netty | Non-blocking TCP for ISO 8583 | Raw sockets | Netty handles backpressure, pooling |
| DynamoDB | Schemaless, auto-scaling | MongoDB | AWS-native, simpler ops |
| React + TypeScript | Type safety, ecosystem | Vue.js | Larger talent pool |

---

## 📝 What You Learned

| # | Concept | Key Takeaway |
|---|---------|--------------|
| 1 | Interview Template | 5 phases: Requirements → Estimate → HLD → Deep Dive → Wrap-up |
| 2 | SQL vs NoSQL | Decision tree based on data relationships and scale needs |
| 3 | Sync vs Async | User-facing = sync, side effects = async |
| 4 | Estimation | QPS = DAU × queries / 86400, Peak = 3× average |
| 5 | Trade-offs | Always state what you chose AND what you sacrificed |
| 6 | Common Mistakes | Don't over-engineer, don't skip failures, discuss trade-offs |
| 7 | One-Page Arch | Entire system explainable in one diagram |
| 8 | CAP Theorem | Payments prioritize consistency, notifications prioritize availability |

---

## 📚 Document Index

| # | Document | Description |
|---|----------|-------------|
| 1 | [system-design-complete-guide.md](./system-design-complete-guide.md) | Full 20-concept guide |
| 2 | [system-design-interview-cheatsheet.md](./system-design-interview-cheatsheet.md) | **This document** |
| 3 | [interview-preparation-guide.md](./interview-preparation-guide.md) | Talking points |
| 4 | [architecture-diagrams.md](./architecture-diagrams.md) | All ASCII diagrams |

---

## 🚀 Next Steps

1. Practice explaining PayFlow architecture in under 5 minutes
2. Do back-of-envelope calculations for PayFlow's scale
3. Rehearse failure scenario discussions
4. Review each service's purpose in one sentence

---

*"The interview is not about the perfect system — it's about showing your thought process."*
