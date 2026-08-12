# PayFlow Payment Gateway — Database Guide

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Type** | Database Reference |
| **Version** | v1.0.0 |
| **Previous** | [API Documentation](api-documentation.md) |
| **Next** | [Troubleshooting Guide](troubleshooting-guide.md) |
| **Databases** | PostgreSQL 15, DynamoDB |
| **Prerequisites** | SQL basics, understanding of PayFlow services |

---

## Table of Contents

1. [Database Architecture](#1-database-architecture)
2. [Identity Database](#2-identity-database)
3. [Payments Database](#3-payments-database)
4. [Merchants Database](#4-merchants-database)
5. [Settlements Database](#5-settlements-database)
6. [Index Strategy](#6-index-strategy)
7. [Sample Queries](#7-sample-queries)
8. [Connection Info](#8-connection-info)
9. [Flyway Migrations](#9-flyway-migrations)
10. [What You Learned](#what-you-learned)
11. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. Database Architecture

PayFlow uses **database-per-service** pattern — each microservice owns its data.

```
┌─────────────────────────────────────────────────────────────────────┐
│                        PostgreSQL Instance                            │
├─────────────────┬──────────────────┬──────────────┬─────────────────┤
│ payflow_identity│ payflow_payments │payflow_merch │payflow_settle   │
│                 │                  │              │                 │
│ • merchants     │ • payment_orders │ • api_keys   │ • settlements   │
│ • refresh_tokens│ • transactions   │ • webhooks   │ • settlement_txn│
│                 │ • refunds        │ • webhook_   │                 │
│                 │ • idempotency_   │   deliveries │                 │
│                 │   keys           │              │                 │
└─────────────────┴──────────────────┴──────────────┴─────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                          DynamoDB                                     │
├─────────────────────────────────┬───────────────────────────────────┤
│ webhook_delivery                │ routing_metrics                    │
│ PK: webhookId, SK: deliveryId  │ PK: routeId, SK: timestamp        │
└─────────────────────────────────┴───────────────────────────────────┘
```

---

## 2. Identity Database

### ER Diagram

```
┌──────────────────────────┐         ┌──────────────────────────┐
│      merchants           │         │    refresh_tokens        │
├──────────────────────────┤         ├──────────────────────────┤
│ PK id (UUID)             │───┐     │ PK id (UUID)             │
│    email (VARCHAR 255)   │   │     │ FK merchant_id (UUID)    │──┐
│    password_hash (TEXT)  │   │     │    token (TEXT)          │  │
│    business_name (255)   │   │     │    expires_at (TIMESTAMP)│  │
│    phone (VARCHAR 20)    │   └─────│    revoked (BOOLEAN)     │  │
│    status (VARCHAR 20)   │         │    created_at (TIMESTAMP)│  │
│    created_at (TIMESTAMP)│         └──────────────────────────┘  │
│    updated_at (TIMESTAMP)│                                        │
└──────────────────────────┘◄──────────────────────────────────────┘
```

### CREATE TABLE Statements

```sql
-- Identity Database: payflow_identity

-- Merchants table (user accounts)
CREATE TABLE merchants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   TEXT NOT NULL,
    business_name   VARCHAR(255) NOT NULL,
    phone           VARCHAR(20),
    gst_number      VARCHAR(20),
    pan             VARCHAR(10),
    website_url     VARCHAR(500),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE, SUSPENDED, DEACTIVATED
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Index for email lookup during login
CREATE UNIQUE INDEX idx_merchants_email ON merchants(email);

-- Refresh tokens for JWT renewal
CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    token           TEXT NOT NULL UNIQUE,
    expires_at      TIMESTAMP NOT NULL,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_merchant ON refresh_tokens(merchant_id);
CREATE INDEX idx_refresh_tokens_token ON refresh_tokens(token);
```

---

## 3. Payments Database

### ER Diagram

```
┌──────────────────────────────┐       ┌──────────────────────────┐
│      payment_orders          │       │       refunds            │
├──────────────────────────────┤       ├──────────────────────────┤
│ PK id (UUID)                 │──┐    │ PK id (UUID)             │
│    order_id (VARCHAR 50)     │  │    │ FK order_id (UUID)       │──┐
│    merchant_id (UUID)        │  │    │    amount (DECIMAL)      │  │
│    amount (DECIMAL 15,2)     │  │    │    reason (TEXT)         │  │
│    currency (VARCHAR 3)      │  │    │    status (VARCHAR 20)   │  │
│    status (VARCHAR 20)       │  │    │    created_at (TIMESTAMP)│  │
│    payment_method (VARCHAR)  │  │    └──────────────────────────┘  │
│    description (TEXT)        │  │                                   │
│    customer_email (VARCHAR)  │  └───────────────────────────────────┘
│    bank_reference_id (VARCHAR│
│    failure_reason (TEXT)     │       ┌──────────────────────────┐
│    metadata (JSONB)          │       │    idempotency_keys      │
│    created_at (TIMESTAMP)    │       ├──────────────────────────┤
│    authorized_at (TIMESTAMP) │       │ PK idempotency_key (VARCHAR)│
│    captured_at (TIMESTAMP)   │       │    merchant_id (UUID)    │
│    updated_at (TIMESTAMP)    │       │    response (JSONB)      │
└──────────────────────────────┘       │    created_at (TIMESTAMP)│
                                       │    expires_at (TIMESTAMP)│
                                       └──────────────────────────┘
```

### CREATE TABLE Statements

```sql
-- Payments Database: payflow_payments

-- Payment orders (core transaction table)
CREATE TABLE payment_orders (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id            VARCHAR(50) NOT NULL UNIQUE,     -- Human-readable: ORD_abc123
    merchant_id         UUID NOT NULL,
    amount              DECIMAL(15, 2) NOT NULL,
    currency            VARCHAR(3) NOT NULL DEFAULT 'INR',
    status              VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    -- Status: CREATED → AUTHORIZED → CAPTURED → SETTLED | FAILED | REFUNDED
    payment_method      VARCHAR(20),                     -- CARD, UPI, NET_BANKING
    description         TEXT,
    customer_email      VARCHAR(255),
    customer_phone      VARCHAR(20),
    bank_reference_id   VARCHAR(100),
    failure_reason      TEXT,
    metadata            JSONB DEFAULT '{}',
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    authorized_at       TIMESTAMP,
    captured_at         TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Indexes for common query patterns
CREATE INDEX idx_orders_merchant_id ON payment_orders(merchant_id);
CREATE INDEX idx_orders_status ON payment_orders(status);
CREATE INDEX idx_orders_created_at ON payment_orders(created_at DESC);
CREATE INDEX idx_orders_merchant_status ON payment_orders(merchant_id, status);
CREATE UNIQUE INDEX idx_orders_order_id ON payment_orders(order_id);

-- Refunds
CREATE TABLE refunds (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID NOT NULL REFERENCES payment_orders(id),
    amount          DECIMAL(15, 2) NOT NULL,
    reason          TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, PROCESSED, FAILED
    bank_reference  VARCHAR(100),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMP
);

CREATE INDEX idx_refunds_order_id ON refunds(order_id);

-- Idempotency keys (prevent duplicate payments)
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    merchant_id     UUID NOT NULL,
    response        JSONB NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL DEFAULT NOW() + INTERVAL '24 hours'
);

CREATE INDEX idx_idempotency_expires ON idempotency_keys(expires_at);

-- Transactions log (immutable audit trail)
CREATE TABLE transactions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID NOT NULL REFERENCES payment_orders(id),
    type            VARCHAR(20) NOT NULL,  -- AUTHORIZE, CAPTURE, REFUND, VOID
    amount          DECIMAL(15, 2) NOT NULL,
    status          VARCHAR(20) NOT NULL,  -- SUCCESS, FAILED
    bank_response   JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transactions_order ON transactions(order_id);
```

---

## 4. Merchants Database

```sql
-- Merchants Database: payflow_merchants

-- API Keys
CREATE TABLE api_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID NOT NULL,
    name            VARCHAR(100) NOT NULL,
    key_hash        TEXT NOT NULL,               -- bcrypt hash of the key
    key_prefix      VARCHAR(20) NOT NULL,        -- First 8 chars for display
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    last_used_at    TIMESTAMP,
    revoked_at      TIMESTAMP
);

CREATE INDEX idx_api_keys_merchant ON api_keys(merchant_id);
CREATE INDEX idx_api_keys_prefix ON api_keys(key_prefix);

-- Webhooks
CREATE TABLE webhooks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID NOT NULL,
    url             VARCHAR(500) NOT NULL,
    secret          TEXT NOT NULL,
    events          TEXT[] NOT NULL,              -- Array of event types
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_webhooks_merchant ON webhooks(merchant_id);
CREATE INDEX idx_webhooks_active ON webhooks(merchant_id, active);
```

---

## 5. Settlements Database

```sql
-- Settlements Database: payflow_settlements

-- Settlement batches
CREATE TABLE settlements (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id         UUID NOT NULL,
    amount              DECIMAL(15, 2) NOT NULL,
    fee_amount          DECIMAL(15, 2) NOT NULL DEFAULT 0,
    net_amount          DECIMAL(15, 2) NOT NULL,
    transaction_count   INTEGER NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- Status: PENDING → PROCESSING → COMPLETED → FAILED
    bank_reference      VARCHAR(100),
    settled_at          TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_settlements_merchant ON settlements(merchant_id);
CREATE INDEX idx_settlements_status ON settlements(status);

-- Settlement ↔ Transaction mapping
CREATE TABLE settlement_transactions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    settlement_id   UUID NOT NULL REFERENCES settlements(id),
    order_id        VARCHAR(50) NOT NULL,
    amount          DECIMAL(15, 2) NOT NULL,
    fee             DECIMAL(15, 2) NOT NULL DEFAULT 0
);

CREATE INDEX idx_settlement_txn_settlement ON settlement_transactions(settlement_id);
```

---

## 6. Index Strategy

| Table | Index | Type | Purpose |
|-------|-------|------|---------|
| `merchants` | `email` | Unique | Login lookup |
| `payment_orders` | `order_id` | Unique | Order lookup by public ID |
| `payment_orders` | `merchant_id, status` | Composite | Merchant dashboard filtering |
| `payment_orders` | `created_at DESC` | B-tree | Time-based pagination |
| `refunds` | `order_id` | B-tree | Find refunds for an order |
| `idempotency_keys` | `expires_at` | B-tree | Cleanup job (TTL-based delete) |
| `api_keys` | `key_prefix` | B-tree | Fast key lookup by prefix |
| `settlements` | `merchant_id` | B-tree | Merchant settlement history |

**Design principles:**
- Index columns used in WHERE clauses
- Composite indexes for multi-column filters (leftmost prefix rule)
- DESC index for time-ordered pagination
- Unique indexes enforce business constraints

---

## 7. Sample Queries

```sql
-- Find all captured orders for a merchant this month
SELECT order_id, amount, payment_method, captured_at
FROM payment_orders
WHERE merchant_id = 'uuid-here'
  AND status = 'CAPTURED'
  AND captured_at >= DATE_TRUNC('month', CURRENT_DATE)
ORDER BY captured_at DESC
LIMIT 20;

-- Calculate daily revenue
SELECT DATE(captured_at) AS day,
       COUNT(*) AS transaction_count,
       SUM(amount) AS total_revenue
FROM payment_orders
WHERE merchant_id = 'uuid-here'
  AND status = 'CAPTURED'
  AND captured_at >= CURRENT_DATE - INTERVAL '30 days'
GROUP BY DATE(captured_at)
ORDER BY day DESC;

-- Payment method breakdown
SELECT payment_method,
       COUNT(*) AS count,
       SUM(amount) AS total,
       ROUND(COUNT(*)::numeric / SUM(COUNT(*)) OVER() * 100, 1) AS percentage
FROM payment_orders
WHERE merchant_id = 'uuid-here' AND status = 'CAPTURED'
GROUP BY payment_method;

-- Success rate
SELECT
  COUNT(*) FILTER (WHERE status = 'CAPTURED') AS success,
  COUNT(*) FILTER (WHERE status = 'FAILED') AS failed,
  ROUND(
    COUNT(*) FILTER (WHERE status = 'CAPTURED')::numeric /
    NULLIF(COUNT(*), 0) * 100, 2
  ) AS success_rate
FROM payment_orders
WHERE merchant_id = 'uuid-here'
  AND created_at >= CURRENT_DATE - INTERVAL '7 days';

-- Expire old idempotency keys (scheduled cleanup)
DELETE FROM idempotency_keys WHERE expires_at < NOW();

-- Update order status (with optimistic locking)
UPDATE payment_orders
SET status = 'AUTHORIZED',
    authorized_at = NOW(),
    bank_reference_id = 'BANK_REF_123',
    updated_at = NOW()
WHERE order_id = 'ORD_abc123'
  AND status = 'CREATED'  -- Ensures valid state transition
RETURNING *;
```

---

## 8. Connection Info

| Database | Service | Port | Default DB Name |
|----------|---------|------|-----------------|
| Identity | identity-service | 5432 | `payflow_identity` |
| Payments | payment-service | 5432 | `payflow_payments` |
| Merchants | merchant-service | 5432 | `payflow_merchants` |
| Settlements | settlement-service | 5432 | `payflow_settlements` |

### Local Development

```yaml
# Spring Boot application.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/payflow_payments
    username: payflow
    password: secret123
```

### Production (AWS RDS)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://payflow-db.xxx.rds.amazonaws.com:5432/payflow_payments
    username: payflow_admin
    password: ${DB_PASSWORD}
```

---

## 9. Flyway Migrations

### Naming Convention

```
V{version}__{description}.sql

V1__create_merchants_table.sql
V2__create_payment_orders_table.sql
V3__add_metadata_column.sql
V4__create_refunds_table.sql
V5__add_settlement_tables.sql
```

| Rule | Example |
|------|---------|
| Prefix `V` for versioned | `V1__init.sql` |
| Double underscore separator | `V2__add_index.sql` |
| Sequential version numbers | V1, V2, V3... |
| Descriptive name | `V3__add_metadata_jsonb_column.sql` |
| Location | `src/main/resources/db/migration/` |

```yaml
# application.yml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
```

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | Database-per-service | Each service owns its database — no shared tables |
| 2 | Schema design | UUID primary keys, timestamps, status columns |
| 3 | ER relationships | Foreign keys within a database, UUIDs across services |
| 4 | Index strategy | Index WHERE clause columns + composite for filters |
| 5 | JSONB | Flexible metadata storage without schema changes |
| 6 | Idempotency | Key-based deduplication with TTL expiry |
| 7 | Flyway | Version-controlled schema migrations |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `relation does not exist` | Migration not run or wrong database | Check Flyway ran: `SELECT * FROM flyway_schema_history` |
| `duplicate key value violates unique constraint` | Retry without idempotency key | Use `ON CONFLICT DO NOTHING` or idempotency |
| `value too long for type varchar(20)` | Status string exceeds column width | Increase column width in migration |
| JSONB query slow | No GIN index on JSONB column | Add: `CREATE INDEX idx_metadata ON orders USING GIN(metadata)` |
| Flyway checksum mismatch | Modified an already-applied migration | Never modify applied migrations; create new ones |
| Connection pool exhausted | Too many concurrent queries | Increase `spring.datasource.hikari.maximum-pool-size` |

---

<div align="center">

**[← API Documentation](api-documentation.md)** | **[Documentation Index](../README.md)** | **[Troubleshooting Guide →](troubleshooting-guide.md)**

</div>
