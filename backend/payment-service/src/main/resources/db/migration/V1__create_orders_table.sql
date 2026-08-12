-- V1: Create orders table
-- Stores payment orders created by merchants

CREATE TABLE IF NOT EXISTS orders (
    id              VARCHAR(30)     PRIMARY KEY,
    merchant_id     VARCHAR(30)     NOT NULL,
    amount          NUMERIC(19, 4)  NOT NULL,
    currency        VARCHAR(3)      NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'CREATED',
    customer_email  VARCHAR(255),
    description     VARCHAR(500),
    receipt_number  VARCHAR(100),
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Indexes for common queries
CREATE INDEX idx_orders_merchant_id ON orders(merchant_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_merchant_status ON orders(merchant_id, status);
CREATE INDEX idx_orders_expires_at ON orders(expires_at) WHERE status = 'CREATED';
