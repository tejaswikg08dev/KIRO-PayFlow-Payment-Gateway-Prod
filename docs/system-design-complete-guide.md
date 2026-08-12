# System Design Complete Guide

## Overview

A standalone system design learning guide covering key concepts, each illustrated with PayFlow examples. Use this as reference for system design interviews.

## 1. Scalability

**Vertical Scaling:** Add more CPU/RAM to existing server
**Horizontal Scaling:** Add more servers

| Type | PayFlow Example | Limitation |
|------|----------------|------------|
| Vertical | Upgrade EC2 from t3.small to t3.large | Hardware ceiling, single point of failure |
| Horizontal | Run 3 Payment Service instances | Requires statelessness, load balancer |

**PayFlow Design:** All services are stateless (state in DB/Redis), enabling horizontal scaling.

## 2. Load Balancing

```
                    ┌─── Payment Service (Instance 1)
Client → ALB ──────┼─── Payment Service (Instance 2)
                    └─── Payment Service (Instance 3)

Algorithms:
- Round Robin: Simple, even distribution
- Least Connections: Route to least-loaded instance
- IP Hash: Same client → same instance (session affinity)
```

**PayFlow:** AWS ALB with health checks. Round robin since services are stateless.

## 3. Caching

| Strategy | Pattern | PayFlow Usage |
|----------|---------|---------------|
| Cache-Aside | App checks cache, falls back to DB | API key validation |
| Write-Through | Write to cache + DB simultaneously | Not used |
| Write-Behind | Write to cache, async flush to DB | Not used |
| TTL-based | Auto-expire cached data | Idempotency keys (24h) |

```
Cache Hit Flow:
Request → Redis (HIT) → Return immediately

Cache Miss Flow:
Request → Redis (MISS) → PostgreSQL → Store in Redis → Return
```

## 4. Database Selection

| Need | Choice | PayFlow Example |
|------|--------|----------------|
| ACID transactions | PostgreSQL | Payment orders, settlements |
| High-write, TTL | DynamoDB | Webhook delivery logs |
| Low-latency cache | Redis | Rate limits, idempotency |
| Event streaming | Kafka | Payment events |

**Decision Framework:**
- Need transactions? → Relational (PostgreSQL)
- Need flexible schema + scale? → Document DB
- Need sub-millisecond? → Redis
- Need time-series? → TimescaleDB / InfluxDB

## 5. Message Queues

```
SYNCHRONOUS (REST):                 ASYNCHRONOUS (Kafka):
Client → Service → Response         Producer → Kafka → Consumer
(blocking, immediate)               (non-blocking, eventual)

PayFlow uses BOTH:
- REST: Payment → Routing (need immediate bank response)
- Kafka: Payment → Webhook (can be delayed)
```

**When to use async:**
- Consumer doesn't need result immediately
- Producer shouldn't fail if consumer is down
- Fan-out to multiple consumers
- Need event replay capability

## 6. Microservices

**PayFlow Bounded Contexts:**

| Context | Service | Data Ownership |
|---------|---------|---------------|
| Authentication | Identity Service | Users, tokens |
| Business Ops | Merchant Service | Merchants, API keys |
| Payments | Payment Service | Orders, transactions |
| Banking | Routing Service | Routing rules |
| Finance | Settlement Service | Batches, payouts |
| Notifications | Webhook + Notification | Delivery logs |

**Communication:** Sync (Feign) for queries, Async (Kafka) for events.

## 7. API Design

```
Resource-Based URLs:
POST   /api/v1/orders          → Create order
GET    /api/v1/orders/{id}     → Get order
GET    /api/v1/orders          → List orders (paginated)
POST   /api/v1/orders/{id}/pay → Process payment (action)

Pagination:
GET /api/v1/orders?page=0&size=20&sort=createdAt,desc

Filtering:
GET /api/v1/orders?status=CAPTURED&dateFrom=2024-01-01
```

## 8. Authentication & Authorization

```
JWT Flow:
Login → Server generates JWT → Client stores token
Request → Client sends Bearer token → Server validates → Allow/Deny

API Key Flow:
Merchant registers → Gets API key → Includes in X-Api-Key header
Gateway → Validates key (Redis cache) → Adds X-Merchant-Id → Routes
```

## 9. Consistency Models

| Model | Guarantee | PayFlow Usage |
|-------|-----------|---------------|
| Strong Consistency | Always see latest write | Payment status (PostgreSQL) |
| Eventual Consistency | Will converge (< 2s) | Webhook delivery, notifications |
| Causal Consistency | See your own writes | Idempotency (Redis) |

## 10. Distributed Transactions

**Problem:** Payment captured in Payment DB, but Kafka event fails.

**Solution: Transactional Outbox Pattern**
```
1. BEGIN TRANSACTION
2. UPDATE orders SET status = 'CAPTURED'
3. INSERT INTO outbox (topic, payload) VALUES ('payment.captured', ...)
4. COMMIT

Separate process:
5. Read outbox table
6. Publish to Kafka
7. Mark outbox record as published
```

## 11. Idempotency

```
Problem: Client retries request → duplicate payment

Solution:
1. Client generates unique idempotency key
2. First request: process + cache result with key
3. Retry: detect key exists → return cached result

Redis:  SET idempotency:key123 response TTL 24h
```

## 12. Rate Limiting

**Token Bucket Algorithm:**
```
Bucket starts with 100 tokens (burst capacity)
Refills at 100 tokens/second
Each request costs 1 token
If bucket empty → reject (429 Too Many Requests)
```

## 13. Circuit Breaker

```
States:
CLOSED → All requests pass through (normal operation)
OPEN → All requests fail immediately (protect downstream)
HALF-OPEN → Allow limited requests to test recovery

PayFlow: Routing Service → Bank connection
- If bank fails 50% of last 20 requests → OPEN
- Wait 60 seconds → HALF-OPEN
- If 3/5 succeed → CLOSED
```

## 14. Event-Driven Architecture

```
PayFlow Event Flow:
Payment Service → publishes "payment.captured"
    │
    ├── Settlement Service (consumes) → calculates fees
    ├── Webhook Service (consumes) → delivers to merchant
    └── Notification Service (consumes) → emails customer
```

## 15. Batch Processing

**PayFlow Settlement:**
- Trigger: Daily at 2:00 AM
- Input: All CAPTURED transactions from yesterday
- Process: Calculate MDR + GST per transaction
- Output: Settlement records + payout initiation
- Tool: Spring Batch (chunked processing, restartable)

## 16. Security

Key principles applied in PayFlow:
- Defense in depth (multiple security layers)
- Least privilege (services access only their own DB)
- Fail securely (errors return generic messages)
- Never trust input (validate everything)

## 17. Monitoring

**Four Golden Signals:**
1. **Latency** — How long requests take (P50, P95, P99)
2. **Traffic** — How many requests (RPS)
3. **Errors** — Failed request rate (%)
4. **Saturation** — How full the system is (CPU, memory, disk)

## 18. CI/CD

```
PayFlow Pipeline:
Code Push → Lint → Build → Unit Test → Integration Test → Docker Build → Push to ECR → Deploy → Health Check
```

## 19. Disaster Recovery

| Metric | Definition | PayFlow Target |
|--------|-----------|----------------|
| RPO | Max data loss acceptable | 0 (no transaction loss) |
| RTO | Max downtime acceptable | < 5 minutes |

Strategy: RDS automated backups (daily) + Redis is ephemeral (cache rebuilt).

## 20. Key Estimation Formulas

```
Storage: records/day × record_size × retention_days
Bandwidth: requests/sec × avg_request_size × 2 (in+out)
Cache size: active_records × record_size
DB connections: services × instances × pool_size
Kafka partitions: target_throughput / per_partition_throughput
```
