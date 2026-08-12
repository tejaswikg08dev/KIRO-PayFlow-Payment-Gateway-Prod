# Phase 1: System Design

## Overview

This document covers the complete system design for PayFlow Payment Gateway — from requirements gathering to capacity planning. Every design decision is justified with trade-off analysis.

## Functional Requirements

| ID | Requirement | Priority |
|----|------------|----------|
| FR-1 | Merchant registration and onboarding | P0 |
| FR-2 | API key generation and management | P0 |
| FR-3 | Payment order creation | P0 |
| FR-4 | Card payment authorization and capture | P0 |
| FR-5 | UPI and Net Banking support | P1 |
| FR-6 | Refund processing (full and partial) | P0 |
| FR-7 | Webhook notifications to merchants | P0 |
| FR-8 | Settlement and payout to merchants | P0 |
| FR-9 | Merchant dashboard with analytics | P1 |
| FR-10 | Hosted checkout page | P0 |
| FR-11 | Transaction search and filtering | P1 |
| FR-12 | Email/SMS notifications to customers | P2 |

## Non-Functional Requirements

| NFR | Target | Rationale |
|-----|--------|-----------|
| Latency (P95) | < 500ms end-to-end | Payment UX must feel instant |
| Availability | 99.9% uptime | Downtime = lost revenue for merchants |
| Throughput | 1000 TPS sustained | Support mid-size merchants |
| Data Durability | Zero transaction loss | Financial data is sacred |
| Security | PCI-DSS Level 3 principles | Card data handling compliance |
| Scalability | Horizontal scaling per service | Handle traffic spikes (sales events) |
| Consistency | Eventually consistent (< 2s) | Webhooks, notifications can lag slightly |
| Recovery | RPO: 0, RTO: < 5 minutes | No data loss, quick recovery |

## Microservices Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         CLIENTS                                       │
│   [Merchant Dashboard]  [Hosted Checkout]  [Merchant Backend]        │
└────────────────────────────┬────────────────────────────────────────┘
                             │ HTTPS
                    ┌────────▼────────┐
                    │   API GATEWAY    │  (JWT + API Key validation)
                    │  Rate Limiting   │  (Redis-backed)
                    └────────┬────────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
┌───────▼──────┐   ┌────────▼───────┐   ┌───────▼──────┐
│  Identity    │   │   Merchant     │   │   Payment    │
│  Service     │   │   Service      │   │   Service    │
│  (Auth/JWT)  │   │  (Onboarding)  │   │  (Orders)    │
└──────────────┘   └────────────────┘   └───────┬──────┘
                                                 │
                                        ┌────────▼───────┐
                                        │   Routing      │
                                        │   Service      │
                                        │  (ISO 8583)    │
                                        └────────┬───────┘
                                                 │ TCP
                                        ┌────────▼───────┐
                                        │ Bank Simulator │
                                        │  (Netty TCP)   │
                                        └────────────────┘

    ═══════════════════ KAFKA BUS ═══════════════════
        │                │                │
┌───────▼──────┐  ┌──────▼───────┐  ┌────▼─────────┐
│  Settlement  │  │   Webhook    │  │ Notification │
│  Service     │  │   Service    │  │   Service    │
│ (Spring Batch)│  │  (Delivery)  │  │  (Email/SMS) │
└──────────────┘  └──────────────┘  └──────────────┘
```

## Database Design

### Database per Service Pattern

| Service | Database | Type | Justification |
|---------|----------|------|---------------|
| Identity Service | `payflow_identity` | PostgreSQL | User credentials, ACID for auth |
| Merchant Service | `payflow_merchant` | PostgreSQL | Merchant config, API keys |
| Payment Service | `payflow_payment` | PostgreSQL | Orders, transactions, ACID |
| Settlement Service | `payflow_settlement` | PostgreSQL | Batch records, payouts |
| Webhook Service | `payflow_webhooks` | DynamoDB | High-write, TTL for cleanup |
| All Services | Redis | In-Memory | Caching, rate limits, idempotency |

### Core Tables Overview

```sql
-- Identity Service
users (id, email, password_hash, role, status, created_at)
refresh_tokens (id, user_id, token, expires_at)

-- Merchant Service  
merchants (id, user_id, business_name, status, mcc_code, created_at)
api_keys (id, merchant_id, key_hash, prefix, status, created_at)
webhook_configs (id, merchant_id, url, secret, events, status)
fee_configs (id, merchant_id, mdr_percent, fixed_fee)

-- Payment Service
orders (id, merchant_id, amount, currency, status, idempotency_key, created_at)
transactions (id, order_id, type, amount, status, rrn, auth_code)
refunds (id, transaction_id, amount, reason, status)

-- Settlement Service
settlement_batches (id, batch_date, status, total_amount, total_fees)
settlement_records (id, batch_id, merchant_id, gross_amount, mdr, gst, net_amount)
payouts (id, merchant_id, amount, utr, status)
```

## API Design Overview

### RESTful Conventions

| Method | Pattern | Purpose |
|--------|---------|---------|
| POST | `/api/v1/orders` | Create payment order |
| GET | `/api/v1/orders/{id}` | Get order details |
| POST | `/api/v1/orders/{id}/pay` | Submit payment |
| POST | `/api/v1/orders/{id}/capture` | Capture authorized payment |
| POST | `/api/v1/refunds` | Initiate refund |
| GET | `/api/v1/transactions` | List transactions (paginated) |

### Response Format

```json
{
  "success": true,
  "data": {
    "orderId": "ord_abc123",
    "amount": 50000,
    "currency": "INR",
    "status": "CREATED"
  },
  "timestamp": "2024-01-15T10:30:00Z"
}
```

## Caching Strategy

| Data | Cache Type | TTL | Eviction |
|------|-----------|-----|----------|
| JWT validation | Redis | 15 min | On logout |
| API key lookup | Redis | 5 min | On key rotation |
| Rate limit counters | Redis | Sliding window | Auto-expire |
| Idempotency keys | Redis | 24 hours | Auto-expire |
| Merchant config | Redis | 10 min | On update |

### Cache-Aside Pattern

```
1. Check Redis for cached data
2. If HIT → return cached value
3. If MISS → query PostgreSQL → store in Redis → return
4. On UPDATE → invalidate cache → write to DB
```

## Event Streaming (Kafka)

### Topic Design

| Topic | Producer | Consumers | Purpose |
|-------|----------|-----------|---------|
| `payment.created` | Payment Service | Notification | Order created event |
| `payment.authorized` | Payment Service | Webhook, Notification | Auth successful |
| `payment.captured` | Payment Service | Settlement, Webhook, Notification | Capture confirmed |
| `payment.failed` | Payment Service | Webhook, Notification | Payment failed |
| `refund.initiated` | Payment Service | Settlement, Webhook | Refund started |
| `refund.completed` | Payment Service | Webhook, Notification | Refund done |
| `settlement.completed` | Settlement Service | Notification | Payout ready |

### Kafka Configuration

```yaml
# Topic settings
partitions: 6          # Parallelism for consumers
replication-factor: 1  # Local dev (3 in production)
retention: 7 days      # Keep events for replay
```

## Security Design

```
┌────────────────────────────────────────────────┐
│              SECURITY LAYERS                    │
├────────────────────────────────────────────────┤
│ Layer 1: TLS/HTTPS (transport encryption)      │
│ Layer 2: API Key (merchant authentication)     │
│ Layer 3: JWT (user session management)         │
│ Layer 4: Rate Limiting (abuse prevention)      │
│ Layer 5: Input Validation (injection prevention)│
│ Layer 6: HMAC Signatures (webhook integrity)   │
│ Layer 7: Encryption at Rest (sensitive data)   │
└────────────────────────────────────────────────┘
```

| Security Measure | Implementation |
|-----------------|----------------|
| Password Hashing | BCrypt (strength 12) |
| Token Format | JWT (RS256 or HS512) |
| API Key Format | `pk_live_` + SHA-256 hash |
| Rate Limiting | Token bucket (Redis) |
| Webhook Signing | HMAC-SHA256 |
| Card Masking | Show only last 4 digits |
| SQL Injection | Parameterized queries (JPA) |
| CORS | Whitelist merchant domains |

## Capacity Planning

### Traffic Estimation

```
Target: 1000 TPS (transactions per second)

Daily transactions: 1000 × 86400 = 86.4 million
Monthly transactions: ~2.6 billion

Storage per transaction: ~2 KB (order + transaction + events)
Daily storage: 86.4M × 2KB = 172.8 GB
Monthly storage: ~5.2 TB

Kafka events per transaction: ~4 events
Kafka throughput: 4000 events/sec
Kafka daily volume: 345.6M events
```

### Infrastructure Sizing (Production)

| Component | Sizing | Justification |
|-----------|--------|---------------|
| Payment Service | 4 instances, 2 CPU, 4GB RAM | Stateless, CPU-bound |
| PostgreSQL | db.r5.large (2 vCPU, 16GB) | Read-heavy with connection pool |
| Redis | cache.r6g.large (2 vCPU, 13GB) | Low latency cache |
| Kafka | 3 brokers, 50GB each | Event durability |
| API Gateway | 2 instances | Entry point redundancy |

---

## Design Decisions

> **📦 Design Decision: Database per Service**
>
> **Choice:** Separate PostgreSQL database per microservice
> **Alternatives:** Shared database, Schema-per-service
> **Rationale:** True data isolation, independent scaling, no coupling. Trade-off: cross-service queries require API calls or events.

> **📦 Design Decision: Kafka over RabbitMQ**
>
> **Choice:** Apache Kafka for event streaming
> **Alternatives:** RabbitMQ, AWS SQS
> **Rationale:** Event replay capability, higher throughput, topic-based pub/sub fits payment events. Trade-off: Higher operational complexity.

> **📦 Design Decision: ISO 8583 over REST for Bank Communication**
>
> **Choice:** ISO 8583 binary protocol over TCP
> **Alternatives:** REST APIs to bank
> **Rationale:** Industry standard for card networks. Real payment gateways use ISO 8583. Demonstrates protocol engineering skills. Trade-off: More complex to implement (Netty + custom codec).

> **📦 Design Decision: Redis for Idempotency**
>
> **Choice:** Redis with 24h TTL for idempotency keys
> **Alternatives:** Database unique constraint
> **Rationale:** Sub-millisecond lookups, automatic expiry, no DB load for duplicate checks. Trade-off: Data loss on Redis failure (mitigated by DB unique constraint as fallback).
