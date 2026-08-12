# Database Guide

## Overview

Complete database reference for all PayFlow services: schemas, tables, indexes, relationships, and sample queries.

## Database Architecture

```
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│payflow_identity  │  │payflow_merchant  │  │payflow_payment   │
│                 │  │                 │  │                 │
│ • users         │  │ • merchants     │  │ • orders        │
│ • refresh_tokens│  │ • api_keys      │  │ • transactions  │
│                 │  │ • webhook_configs│  │ • refunds       │
│                 │  │ • fee_configs   │  │                 │
└─────────────────┘  └─────────────────┘  └─────────────────┘

┌─────────────────┐  ┌─────────────────┐
│payflow_settlement│  │ DynamoDB        │
│                 │  │                 │
│ • batches       │  │ • webhook_      │
│ • records       │  │   deliveries    │
│ • payouts       │  │                 │
└─────────────────┘  └─────────────────┘
```

## Identity Service Database

### users

```sql
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'MERCHANT',
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_status ON users(status);
```

### refresh_tokens

```sql
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_refresh_tokens_token ON refresh_tokens(token);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
```

### Sample Queries

```sql
-- Find user by email (login)
SELECT * FROM users WHERE email = 'merchant@example.com' AND status = 'ACTIVE';

-- Get valid refresh token
SELECT rt.*, u.email FROM refresh_tokens rt
JOIN users u ON rt.user_id = u.id
WHERE rt.token = $1 AND rt.revoked = FALSE AND rt.expires_at > NOW();

-- Revoke all tokens for user (logout)
UPDATE refresh_tokens SET revoked = TRUE WHERE user_id = $1;
```

## Payment Service Database

### orders

```sql
CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    status VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    description VARCHAR(500),
    receipt_number VARCHAR(100),
    customer_email VARCHAR(255),
    customer_phone VARCHAR(20),
    idempotency_key VARCHAR(255) UNIQUE,
    callback_url VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    expires_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_orders_merchant ON orders(merchant_id, created_at DESC);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_idempotency ON orders(idempotency_key);
CREATE INDEX idx_orders_created ON orders(created_at DESC);
```

### transactions

```sql
CREATE TABLE transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),
    type VARCHAR(30) NOT NULL,  -- AUTHORIZATION, CAPTURE, VOID
    amount BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,  -- SUCCESS, FAILED, TIMEOUT
    rrn VARCHAR(12),
    auth_code VARCHAR(6),
    response_code VARCHAR(2),
    payment_method VARCHAR(30),
    card_last4 VARCHAR(4),
    card_network VARCHAR(20),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_txn_order ON transactions(order_id);
CREATE INDEX idx_txn_rrn ON transactions(rrn);
CREATE INDEX idx_txn_merchant ON transactions(order_id);  -- via join
```

### refunds

```sql
CREATE TABLE refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id),
    amount BIGINT NOT NULL CHECK (amount > 0),
    reason VARCHAR(255),
    status VARCHAR(30) NOT NULL DEFAULT 'INITIATED',
    rrn VARCHAR(12),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP
);

CREATE INDEX idx_refunds_txn ON refunds(transaction_id);
CREATE INDEX idx_refunds_status ON refunds(status);
```

### Sample Queries

```sql
-- Get order with latest transaction
SELECT o.*, t.status as txn_status, t.rrn, t.auth_code
FROM orders o
LEFT JOIN transactions t ON o.id = t.order_id
WHERE o.id = $1
ORDER BY t.created_at DESC LIMIT 1;

-- List merchant orders (paginated)
SELECT * FROM orders
WHERE merchant_id = $1
ORDER BY created_at DESC
LIMIT $2 OFFSET $3;

-- Get total captured amount for settlement
SELECT merchant_id, SUM(amount) as total_amount, COUNT(*) as txn_count
FROM orders
WHERE status = 'CAPTURED'
  AND created_at BETWEEN $1 AND $2
GROUP BY merchant_id;

-- Check refund eligibility
SELECT COALESCE(SUM(r.amount), 0) as refunded_amount
FROM refunds r
WHERE r.transaction_id = $1 AND r.status != 'FAILED';
```

## Settlement Service Database

### settlement_batches

```sql
CREATE TABLE settlement_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_date DATE NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_gross_amount BIGINT DEFAULT 0,
    total_fees BIGINT DEFAULT 0,
    total_net_amount BIGINT DEFAULT 0,
    transaction_count INT DEFAULT 0,
    started_at TIMESTAMP,
    completed_at TIMESTAMP
);
```

### Sample Queries

```sql
-- Get merchant's settlement history
SELECT sb.batch_date, SUM(sr.gross_amount) as gross,
       SUM(sr.mdr_amount + sr.gst_amount) as fees,
       SUM(sr.net_amount) as net
FROM settlement_records sr
JOIN settlement_batches sb ON sr.batch_id = sb.id
WHERE sr.merchant_id = $1
GROUP BY sb.batch_date
ORDER BY sb.batch_date DESC
LIMIT 30;

-- Get payout history
SELECT * FROM payouts
WHERE merchant_id = $1
ORDER BY initiated_at DESC;
```

## Index Strategy

| Table | Index | Purpose | Type |
|-------|-------|---------|------|
| users | email | Login lookup | Unique B-tree |
| orders | merchant_id + created_at | List orders | Composite B-tree |
| orders | idempotency_key | Duplicate check | Unique B-tree |
| orders | status | Filter by state | B-tree |
| transactions | rrn | Bank reference lookup | B-tree |
| api_keys | key_hash | API key validation | Unique B-tree |

## Data Volume Estimation

| Table | Records/Day | Growth/Month | Storage/Month |
|-------|-------------|--------------|---------------|
| orders | 10,000 | 300,000 | ~150MB |
| transactions | 15,000 | 450,000 | ~200MB |
| refunds | 500 | 15,000 | ~10MB |
| settlement_records | 10,000 | 300,000 | ~100MB |
