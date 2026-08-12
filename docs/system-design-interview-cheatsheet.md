# System Design Interview Cheatsheet

## Quick Reference for 45-Minute System Design Interviews

## SQL vs NoSQL Decision Matrix

| Factor | Choose SQL (PostgreSQL) | Choose NoSQL (DynamoDB/MongoDB) |
|--------|------------------------|--------------------------------|
| Data relationships | Complex joins needed | Denormalized, few joins |
| Consistency | ACID required | Eventual consistency OK |
| Schema | Well-defined, stable | Flexible, evolving |
| Scale pattern | Vertical + read replicas | Horizontal sharding |
| Query pattern | Complex queries, aggregations | Simple key-value lookups |
| Transaction | Multi-row transactions | Single-document atomic |
| **PayFlow Example** | Orders, merchants, settlements | Webhook delivery logs |

## Sync vs Async Decision Matrix

| Factor | Synchronous (REST) | Asynchronous (Queue/Event) |
|--------|-------------------|---------------------------|
| Response needed? | Yes, immediately | No, can be delayed |
| Failure impact | Caller fails too | Caller unaffected |
| Coupling | Tight (both must be up) | Loose (producer independent) |
| Latency budget | Within user-facing timeout | Can take seconds/minutes |
| Fan-out | 1:1 communication | 1:N broadcast |
| **PayFlow Example** | Payment → Bank | Payment → Webhook delivery |

## Back-of-Envelope Estimation Formulas

```
═══ STORAGE ═══
Daily storage = daily_records × avg_record_size
Monthly storage = daily_storage × 30
Yearly storage = monthly × 12

═══ BANDWIDTH ═══
Incoming BW = write_requests/sec × avg_request_size
Outgoing BW = read_requests/sec × avg_response_size
Total BW = incoming + outgoing

═══ MEMORY (Cache) ═══
Cache size = hot_records × record_size × 1.2 (overhead)
Cache hit rate target: 80-90%

═══ COMMON NUMBERS ═══
1 day = 86,400 seconds
1 million requests/day ≈ 12 requests/second
1 GB = 1 billion bytes
1 UUID = 16 bytes (binary) or 36 bytes (string)
1 average DB row ≈ 500 bytes - 2 KB
```

### PayFlow Estimation Example

```
Target: 1000 TPS

Daily transactions: 1000 × 86,400 = 86.4M
Storage per txn: ~2 KB (order + transaction rows)
Daily storage: 86.4M × 2 KB = 172.8 GB/day
Monthly storage: ~5 TB

Kafka events per txn: 4
Kafka throughput: 4000 events/sec
Kafka daily: 345M events

Redis (idempotency): 86.4M keys × 200 bytes = ~17 GB
Redis (with 24h TTL): ~17 GB max at any time
```

## Common Mistakes to Avoid

| Mistake | Better Approach |
|---------|----------------|
| Jump into solution immediately | Clarify requirements first (5 min) |
| Design everything at once | Start with core flow, then expand |
| Ignore trade-offs | State trade-off for every decision |
| Skip failure scenarios | Discuss: "What if X fails?" |
| Over-engineer for scale | Design for current need, plan for growth |
| Forget non-functional requirements | Ask about latency, consistency, availability |
| Ignore data model | Define entities early |
| No API design | Show key endpoints |

## 45-Minute Interview Template

### Minutes 0-5: Clarify Requirements
```
- What are the core use cases?
- How many users/requests?
- What's the consistency requirement?
- What's the latency budget?
- Read-heavy or write-heavy?
- Any specific constraints?
```

### Minutes 5-10: High-Level Design
```
- Draw main components (boxes + arrows)
- Identify data stores
- Show communication patterns
- Identify sync vs async flows
```

### Minutes 10-25: Detailed Design
```
- API design (key endpoints)
- Data model (key tables/collections)
- Core algorithm/flow
- Pick 2-3 most important components and go deep
```

### Minutes 25-35: Scale & Reliability
```
- How to handle 10x traffic?
- What if DB goes down?
- What if a service crashes?
- Caching strategy
- Load balancing
```

### Minutes 35-40: Trade-offs & Alternatives
```
- Why this database over another?
- Why sync over async (or vice versa)?
- What are we sacrificing?
- What would you do differently with unlimited budget?
```

### Minutes 40-45: Monitoring & Operations
```
- Key metrics to monitor
- How to detect failures
- Deployment strategy
- Data backup/recovery
```

## Quick Architecture Patterns

### API Gateway Pattern
```
Clients → Gateway → [Auth, Rate Limit, Route] → Microservices
```

### Event Sourcing
```
Write: Command → Event Store (append-only)
Read: Event Store → Projections (materialized views)
```

### CQRS (Command Query Responsibility Segregation)
```
Write Model: Normalized, write-optimized
Read Model: Denormalized, query-optimized
Sync: Events from write → update read model
```

### Saga Pattern (Distributed Transactions)
```
Orchestration: Central coordinator manages steps
Choreography: Each service publishes events, next service reacts

PayFlow uses Choreography:
Payment captured → Event → Settlement processes → Event → Webhook delivers
```

## Technology Selection Cheatsheet

| Need | Technology | Why |
|------|-----------|-----|
| API Framework | Spring Boot | Enterprise-grade, ecosystem |
| Relational DB | PostgreSQL | Open source, reliable, JSON support |
| Cache | Redis | Sub-ms latency, data structures |
| Message Queue | Kafka | Replay, throughput, durability |
| Search | Elasticsearch | Full-text search, analytics |
| Object Storage | S3 | Unlimited, cheap, durable |
| CDN | CloudFront | Global edge caching |
| Container | Docker | Portable, reproducible |
| Orchestration | Kubernetes / ECS | Auto-scaling, self-healing |
| CI/CD | GitHub Actions | Free, integrated |
| Monitoring | CloudWatch / Datadog | Metrics + logs + alerts |

## Scaling Strategies Summary

```
Service Layer: Horizontal scaling (add instances)
Database: Read replicas → Sharding → NewSQL
Cache: Cluster mode (Redis Cluster)
Queue: Add partitions (Kafka)
Storage: CDN for static, S3 for objects
Compute: Auto-scaling groups
Network: Load balancers, CDN edge
```
