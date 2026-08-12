-- V4: Create refunds table
-- Stores refunds issued against captured payments (full and partial)

CREATE TABLE IF NOT EXISTS refunds (
    id              VARCHAR(30)     PRIMARY KEY,
    payment_id      VARCHAR(30)     NOT NULL,
    merchant_id     VARCHAR(30)     NOT NULL,
    amount          NUMERIC(19, 4)  NOT NULL,
    reason          VARCHAR(500),
    status          VARCHAR(20)     NOT NULL DEFAULT 'PROCESSED',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_refunds_payment FOREIGN KEY (payment_id) REFERENCES payments(id)
);

-- Indexes for common queries
CREATE INDEX idx_refunds_payment_id ON refunds(payment_id);
CREATE INDEX idx_refunds_merchant_id ON refunds(merchant_id);
