-- V3: Create payment_methods table
-- Stores masked/tokenized payment method details per payment

CREATE TABLE IF NOT EXISTS payment_methods (
    id                  BIGSERIAL       PRIMARY KEY,
    payment_id          VARCHAR(30)     NOT NULL,
    type                VARCHAR(20)     NOT NULL,
    card_last4          VARCHAR(4),
    card_brand          VARCHAR(20),
    card_expiry_month   VARCHAR(2),
    card_expiry_year    VARCHAR(4),
    upi_id              VARCHAR(100),
    bank_code           VARCHAR(20),
    bank_name           VARCHAR(100),

    CONSTRAINT fk_payment_methods_payment FOREIGN KEY (payment_id) REFERENCES payments(id)
);

-- Index for lookup by payment
CREATE INDEX idx_payment_methods_payment_id ON payment_methods(payment_id);
