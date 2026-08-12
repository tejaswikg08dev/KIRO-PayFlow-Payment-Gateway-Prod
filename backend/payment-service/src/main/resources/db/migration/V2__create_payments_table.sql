-- V2: Create payments table
-- Stores payment attempts and their lifecycle states

CREATE TABLE IF NOT EXISTS payments (
    id                  VARCHAR(30)     PRIMARY KEY,
    order_id            VARCHAR(30)     NOT NULL,
    merchant_id         VARCHAR(30)     NOT NULL,
    amount              NUMERIC(19, 4)  NOT NULL,
    currency            VARCHAR(3)      NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'CREATED',
    payment_method      VARCHAR(20)     NOT NULL,
    authorization_code  VARCHAR(50),
    bank_reference_id   VARCHAR(100),
    failure_reason      VARCHAR(500),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders(id)
);

-- Indexes for common queries
CREATE INDEX idx_payments_order_id ON payments(order_id);
CREATE INDEX idx_payments_merchant_id ON payments(merchant_id);
CREATE INDEX idx_payments_merchant_status ON payments(merchant_id, status);
CREATE INDEX idx_payments_status ON payments(status);
