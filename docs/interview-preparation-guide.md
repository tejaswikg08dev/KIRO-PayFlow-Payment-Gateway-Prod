# Interview Preparation Guide — PayFlow Payment Gateway

| Field | Details |
|-------|---------|
| **Project** | PayFlow Payment Gateway |
| **Document Type** | Interview Preparation & Talking Points |
| **Target Roles** | Backend Engineer, Senior Backend Engineer, Platform Engineer |
| **Last Updated** | 2024 |
| **Audience** | You (the developer), before a technical interview |

---

## Table of Contents
- [Elevator Pitches](#elevator-pitches)
- [Key Architecture Decisions](#architecture-decisions)
- [System Design Questions This Prepares You For](#system-design-questions)
- [Walk Me Through a Payment](#walk-through-payment)
- [How Do You Handle Failures?](#handle-failures)
- [How Does This Scale?](#how-scale)
- [Technical Deep-Dive Topics](#deep-dive)
- [Behavioral Questions Mapped to Project](#behavioral)
- [Numbers to Know](#numbers)
- [Common Follow-Up Questions](#follow-ups)

---

## Elevator Pitches

### 30-Second Version
> "I built PayFlow, a production-grade payment gateway using Spring Cloud microservices. It processes card payments end-to-end — from merchant API call through ISO 8583 protocol to bank simulators — with idempotency, smart routing, circuit breakers, and real-time webhooks. It's deployed on AWS with Docker and CI/CD pipelines."

### 2-Minute Version
> "PayFlow is a full payment processing platform I designed and built from scratch. The architecture has 8 microservices: API gateway for rate limiting and auth, identity service for JWT-based authentication, merchant service for onboarding, payment service for transaction orchestration, routing service that speaks ISO 8583 over TCP to banks, settlement service for batch processing, webhook service for merchant notifications via Kafka, and a notification service.
>
> Key technical challenges I solved: implementing the ISO 8583 binary protocol with Netty for async TCP, ensuring exactly-once payment processing with idempotency keys, building smart routing that selects the optimal bank based on success rates and latency, and adding circuit breakers to gracefully handle bank outages.
>
> The frontend is a React dashboard showing real-time transaction analytics. Everything runs in Docker with GitHub Actions CI/CD, deployed to AWS ECS."

### 5-Minute Version
Use the 2-minute version as foundation, then expand on:
- The ISO 8583 protocol challenge (binary parsing, bitmap encoding)
- Kafka event-driven architecture for webhooks and settlements
- The smart routing algorithm (weighted scoring: cost 30%, success rate 40%, latency 30%)
- Observability: distributed tracing with correlation IDs across all services
- Testing strategy: unit, integration, contract tests with Testcontainers

---

## Key Architecture Decisions

| Decision | Why (30-second answer) |
|----------|----------------------|
| Microservices over monolith | Payment systems need independent scaling — routing handles 10x more load than settlement. Also, bank connectivity has different availability requirements than the dashboard. |
| ISO 8583 over REST to banks | Real banks use ISO 8583 over TCP. Building this proves I understand the actual protocol, not just REST wrappers. Shows I can work with binary protocols. |
| Kafka for async events | Webhooks and settlements don't need synchronous processing. Kafka gives us ordering, replay capability, and backpressure handling for free. |
| Idempotency keys | Network failures happen. Without idempotency, a retry could charge a customer twice. This is non-negotiable in payments. |
| Circuit breaker pattern | If Bank A is down, we don't want to exhaust our thread pool waiting for timeouts. Circuit breaker fails fast and routes to Bank B. |
| API Gateway pattern | Single entry point for rate limiting, authentication, and routing. Avoids duplicating security logic in every service. |
| JWT + API Key dual auth | JWT for dashboard users (humans), API keys for server-to-server merchant integrations (machines). Different use cases need different auth mechanisms. |
| Event sourcing for payments | Payment state transitions are append-only events. We never lose history, and we can reconstruct state at any point in time for audit purposes. |

---

## System Design Questions This Prepares You For

| Interview Question | Your Approach (using PayFlow experience) |
|-------------------|----------------------------------------|
| "Design a payment system" | Direct experience. Walk through PayFlow architecture layer by layer. |
| "Design a rate limiter" | Explain Redis sliding window implementation from API Gateway. |
| "Design a notification/webhook system" | Kafka consumer → retry with exponential backoff → DLQ for failures. |
| "Design an API gateway" | Spring Cloud Gateway: routing, filters, rate limiting, auth propagation. |
| "Design for high availability" | Circuit breakers, smart routing failover, async processing, health checks. |
| "Design an event-driven system" | Kafka topics, consumer groups, exactly-once semantics, dead letter queues. |
| "Design for idempotency" | Redis-backed idempotency keys with TTL, request fingerprinting. |
| "Design a service mesh" | Eureka discovery, config server, load balancing, distributed tracing. |

---

## "Walk Me Through a Payment" — Answer Template

```
"When a merchant calls POST /v1/payments/authorize:

1. API GATEWAY: Rate limit check (Redis sliding window) → JWT/API key
   validation → route to payment-service

2. PAYMENT SERVICE: Validate request → check idempotency key →
   create Payment entity (PENDING) → call routing-service via Feign

3. ROUTING SERVICE: Run fraud checks → smart routing selects best bank
   (based on success rate, cost, latency) → build ISO 8583 message →
   send over Netty TCP to bank

4. BANK: Receives ISO 8583 → checks card, balance, fraud →
   returns response code (00=approved, 51=insufficient funds)

5. RESPONSE PATH: Routing parses ISO 8583 response → returns to
   payment-service → updates Payment status (AUTHORIZED/DECLINED) →
   publishes Kafka event → returns response through gateway to merchant

6. ASYNC: Kafka event triggers webhook-service → signs payload with
   HMAC → delivers to merchant's webhook URL with retry logic"
```

**Key phrase to use:** "The entire flow takes under 500ms for the synchronous path."

---

## "How Do You Handle Failures?" — Answer Template

```
"PayFlow handles failures at every layer:

NETWORK FAILURES (merchant → PayFlow):
  → Idempotency keys ensure retries don't create duplicate charges
  → Client receives a timeout, retries with same idempotency key, gets same result

SERVICE FAILURES (between microservices):
  → Circuit breaker pattern (Resilience4j): after 5 failures, circuit opens
  → Requests fail fast instead of waiting for timeout
  → Half-open state tests recovery every 30 seconds

BANK FAILURES (PayFlow → bank):
  → Smart routing detects bank is unhealthy (success rate drops)
  → Automatically routes to backup bank
  → If all banks down: return 'issuer unavailable' and allow merchant to retry

DATA CONSISTENCY:
  → Payment state machine prevents invalid transitions
  → Event sourcing means we never lose transaction history
  → Kafka guarantees at-least-once delivery; idempotent consumers handle duplicates

WEBHOOK DELIVERY FAILURES:
  → Exponential backoff: retry at 1min, 5min, 30min, 2hr, 24hr
  → Dead letter queue after max retries
  → Merchant can query payment status as fallback"
```

---

## "How Does This Scale?" — Answer Template

```
"PayFlow scales horizontally at each layer:

STATELESS SERVICES:
  → All services are stateless (state in DB/Redis/Kafka)
  → Spin up more instances behind load balancer
  → Eureka handles service discovery automatically

DATABASE:
  → Read replicas for dashboard queries
  → Connection pooling (HikariCP, 20 connections per instance)
  → Partition payments table by date for query performance

KAFKA:
  → Add partitions to increase parallelism
  → Consumer groups scale with partition count
  → Separate topics per event type (payment.authorized, payment.captured)

RATE LIMITING:
  → Redis cluster for distributed rate limit state
  → Sliding window is O(log n) — scales with request volume

BANK CONNECTIONS:
  → Netty connection pooling (non-blocking I/O)
  → One thread handles thousands of concurrent TCP connections
  → Smart routing distributes load across multiple banks

ESTIMATED CAPACITY:
  → Single instance: ~500 TPS
  → 3 instances + Redis cluster: ~1500 TPS
  → Production target: 10,000 TPS with horizontal scaling"
```

---

## Technical Deep-Dive Topics

Be prepared to go deep on any of these:

### 1. ISO 8583 Protocol
- Binary message format: MTI + Bitmap + Data Fields
- Bitmap encoding: 64-bit field presence indicator
- Field types: NUMERIC (fixed), LLVAR/LLLVAR (variable length)
- Why banks still use this (efficiency, proven reliability)

### 2. Kafka Event Architecture
- Topics: `payment.events`, `webhook.delivery`, `settlement.batch`
- Consumer groups for parallel processing
- Exactly-once semantics with idempotent producers
- Dead letter queue for poison messages

### 3. Idempotency Implementation
- Redis key: `idempotency:{merchantId}:{key}` with 24h TTL
- Store full response (status + body) on first execution
- Return cached response on duplicate requests
- Race condition handling: Redis SETNX for distributed lock

### 4. Circuit Breaker (Resilience4j)
- States: CLOSED → OPEN → HALF_OPEN → CLOSED
- Metrics: failure rate, slow call rate, number of calls
- Fallback: return cached response or route to alternate bank
- Monitoring: Actuator endpoints expose circuit state

### 5. Smart Routing Algorithm
- Scoring: cost (30%) + success_rate (40%) + latency (30%)
- Real-time metrics from sliding window (last 100 transactions)
- Fallback order if primary bank is circuit-open
- A/B testing: send 5% traffic to new bank to collect metrics

---

## Behavioral Questions Mapped to Project

| Question | Your PayFlow Story |
|----------|-------------------|
| "Tell me about a challenging technical problem" | Implementing ISO 8583 bitmap parsing — had to understand bit manipulation at the byte level, no good Java libraries existed for our exact needs. Built custom builder/parser with full roundtrip test coverage. |
| "Describe a trade-off decision you made" | Chose eventual consistency for webhooks over synchronous delivery. Trade-off: merchants don't get instant notification, but the payment response isn't blocked by webhook delivery. Result: 3x faster response times. |
| "How did you handle a tight deadline?" | Needed smart routing before the bank simulator was ready. Built the routing logic with a mock bank interface, then swapped in the real TCP client later. Interface segregation saved weeks. |
| "How do you approach testing?" | Layered strategy: unit tests for business logic, integration tests with Testcontainers for DB/Kafka, contract tests between services. Found 3 edge cases in idempotency logic through property-based testing. |
| "Describe a time you disagreed with a design" | Initially planned synchronous webhooks. Realized this couples payment latency to merchant server speed. Proposed Kafka-based async delivery. Proved it with latency benchmarks. |

---

## Numbers to Know

| Metric | Value | Source |
|--------|-------|--------|
| Authorization latency (P50) | < 200ms | Target for synchronous path |
| Authorization latency (P99) | < 500ms | Including bank roundtrip |
| Target TPS (single instance) | 500 | Netty + connection pooling |
| Target TPS (cluster of 3) | 1,500 | Horizontal scaling |
| Idempotency key TTL | 24 hours | Redis |
| Circuit breaker threshold | 5 failures → open | Resilience4j config |
| Rate limit (per merchant) | 1,000 req/min | Redis sliding window |
| Webhook retry schedule | 1m, 5m, 30m, 2h, 24h | Exponential backoff |
| JWT access token TTL | 15 minutes | Security best practice |
| Kafka partition count | 6 per topic | Parallelism target |
| Database connection pool | 20 per service instance | HikariCP |
| Payment record size | ~2KB | PostgreSQL row estimate |
| Daily transactions (design target) | 10M | ~115 TPS average |
| Storage per month | ~60GB | 10M × 2KB × 30 days |

---

## Common Follow-Up Questions

| Question | How to Answer |
|----------|---------------|
| "Why not use Stripe/Adyen SDK?" | "The point was to understand what happens inside those SDKs. I built the ISO 8583 layer, the routing logic, and the reliability patterns that companies like Stripe implement internally." |
| "Is this production-ready?" | "It demonstrates production patterns — idempotency, circuit breakers, observability. For actual production, I'd add PCI-DSS compliance audits, HSM for key management, and load testing at scale." |
| "How would you add a new payment method?" | "The routing service uses a strategy pattern. Add a new `PaymentProcessor` implementation, register it in the router config, and the smart routing algorithm picks it up automatically." |
| "What would you do differently?" | "I'd add distributed tracing earlier (Zipkin/Jaeger) — debugging cross-service issues was harder without it. I'd also consider CQRS for the payment query side to separate read/write scaling." |
| "How do you monitor this in production?" | "Spring Boot Actuator exposes health, metrics, and circuit breaker state. Prometheus scrapes metrics, Grafana dashboards show TPS, latency percentiles, error rates. Alerts on P99 > 500ms or error rate > 1%." |
| "What about data consistency?" | "Saga pattern for distributed transactions. Each service publishes events on success. Compensating transactions on failure (e.g., reverse authorization if capture fails)." |
| "How do you handle PCI compliance?" | "Tokenization after first use, PAN masking in logs, encrypted transit (TLS), no CVV storage. In production, I'd use a certified PCI-DSS vault like Basis Theory or AWS CloudHSM." |
| "Why Spring Cloud over Kubernetes native?" | "Spring Cloud gave me deeper understanding of service mesh internals (discovery, config, circuit breaking). In production, I'd consider Kubernetes service mesh (Istio) for infrastructure-level concerns." |

---

## Interview Day Reminders

1. **Start with the big picture** — show the architecture diagram, explain the flow
2. **Use specific numbers** — latency targets, TPS, storage estimates show you've thought deeply
3. **Mention trade-offs** — every decision has a downside, interviewers want to hear you acknowledge them
4. **Connect to the role** — tie PayFlow patterns to the problems the company faces
5. **Be honest about gaps** — "I haven't load-tested at 10K TPS yet, but here's how I'd approach it"

---

*Review this guide the night before your interview. Focus on the sections most relevant to the role you're interviewing for.*
