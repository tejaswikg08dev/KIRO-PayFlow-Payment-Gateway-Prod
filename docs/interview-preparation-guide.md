# Interview Preparation Guide

## Overview

Talking points for explaining PayFlow in technical interviews. Covers how to present design decisions, handle follow-up questions, and demonstrate depth of understanding.

## How to Introduce the Project (30-second pitch)

> "I built a production-grade payment gateway called PayFlow. It handles card payments from authorization through settlement using 9 microservices, event-driven architecture with Kafka, ISO 8583 protocol for bank communication, and is deployed on AWS. The system processes payments through a state machine, calculates merchant fees, and delivers webhooks with guaranteed delivery."

## Key Design Decisions to Discuss

### 1. Why Microservices?

**Question:** "Why not a monolith?"

**Answer:**
- Payment gateway has clear bounded contexts (auth, payments, settlements, notifications)
- Each service has different scaling needs (payment service gets more traffic)
- Team independence (in real org, different teams own different services)
- Fault isolation (webhook failure shouldn't affect payment processing)
- **Trade-off acknowledged:** Added complexity, network latency, distributed transactions

### 2. Why Kafka over REST for Events?

**Question:** "Why not just call the webhook/notification service directly?"

**Answer:**
- Decoupling: Payment Service doesn't need to know about consumers
- Reliability: Events persisted in Kafka, consumers can replay if they crash
- Backpressure: Consumers process at their own pace
- Fan-out: One event → multiple consumers (webhook + notification + settlement)
- **Trade-off:** Eventual consistency, debugging is harder

### 3. Why ISO 8583 over REST for Bank Communication?

**Question:** "Banks have REST APIs now, why use a binary protocol?"

**Answer:**
- Industry standard: All card networks (Visa, Mastercard) use ISO 8583
- Performance: Binary encoding is more compact than JSON
- Learning opportunity: Demonstrates protocol engineering with Netty
- Real-world relevance: Actual payment gateways use this
- **In production:** Both exist. REST for newer APIs, ISO 8583 for card network

### 4. Why Redis for Idempotency?

**Question:** "Can't you just use a database unique constraint?"

**Answer:**
- Sub-millisecond lookups (Redis) vs. millisecond (DB)
- Automatic expiry with TTL (no cleanup job needed)
- Reduces load on PostgreSQL
- **Fallback:** DB unique constraint on idempotency_key as backup
- **Trade-off:** Redis failure means possible duplicate (mitigated by DB constraint)

### 5. Why Database-per-Service?

**Question:** "Isn't that overkill? How do you query across services?"

**Answer:**
- True isolation: Schema changes don't affect other services
- Independent scaling: Payment DB can scale separately
- Technology freedom: Could use DynamoDB for webhooks, PostgreSQL for payments
- **Cross-service queries:** API calls or materialized views from events
- **Trade-off:** No JOINs across services, eventual consistency

## Common Follow-Up Questions

### Scalability

**Q: "How would you scale to 10,000 TPS?"**
- Horizontal scaling of stateless services (Payment, Routing)
- Read replicas for PostgreSQL
- Increase Kafka partitions (more parallel consumers)
- Connection pooling (HikariCP tuned)
- Caching hot data (merchant configs, API keys)
- Sharding by merchant ID if needed

### Consistency

**Q: "What if a payment succeeds at the bank but your DB write fails?"**
- Transactional outbox pattern: Write order + event in same DB transaction
- Separate publisher reads outbox and sends to Kafka
- Idempotent consumers handle duplicates
- Reconciliation job compares with bank records daily

### Failure Handling

**Q: "What happens if Kafka is down?"**
- Circuit breaker prevents cascading failure
- Events stored in database outbox table
- Background job retries publishing when Kafka recovers
- Payment still succeeds (synchronous part is REST to bank)
- Downstream effects (webhook, notification) are delayed, not lost

### Security

**Q: "How do you handle card data securely?"**
- Card numbers never stored (pass-through only)
- Masked in all logs (show last 4 only)
- HTTPS/TLS for all transmission
- ISO 8583 over TCP (binary, not logged in full)
- In production: would use PCI-DSS compliant tokenization (Stripe-like)

## Architecture Walkthrough Script

```
1. "The entry point is the API Gateway built with Spring Cloud Gateway..."
2. "It validates JWT tokens for dashboard users and API keys for merchant backends..."
3. "Requests are routed to the appropriate microservice..."
4. "For payments, the Payment Service manages a state machine: CREATED → PROCESSING → AUTHORIZED → CAPTURED..."
5. "The Routing Service handles fraud detection and builds ISO 8583 messages..."
6. "These are sent over TCP to the bank (simulated with our Netty-based Bank Simulator)..."
7. "Once authorized, events are published to Kafka..."
8. "The Webhook Service delivers notifications to merchants with HMAC signatures and exponential backoff retry..."
9. "Daily, the Settlement Service runs a Spring Batch job to calculate fees and initiate payouts..."
```

## Questions to Ask Back

- "What scale does your payment system operate at?"
- "Do you use saga pattern or two-phase commit for distributed transactions?"
- "How do you handle payment reconciliation with banks?"

## Numbers to Know

| Metric | Value | Context |
|--------|-------|---------|
| Target TPS | 1,000 | Design target |
| P95 Latency | < 500ms | End-to-end |
| Availability | 99.9% | Uptime SLA |
| Services | 9 | Microservices count |
| Databases | 4 PostgreSQL + 1 DynamoDB | Data stores |
| Kafka Topics | 7 | Event channels |
| Settlement | Daily 2AM | Batch schedule |
| Webhook Retries | 6 attempts | Exponential backoff |
| JWT Expiry | 15 minutes | Short-lived |
| API Key Format | SHA-256 | Storage format |
