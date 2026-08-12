CREATE TABLE settlement_records (
    id              VARCHAR(36) PRIMARY KEY,
    batch_id        VARCHAR(36) NOT NULL REFERENCES settlement_batches(id),
    merchant_id     VARCHAR(36) NOT NULL,
    gross_amount    NUMERIC(19, 4) DEFAULT 0,
    refund_amount   NUMERIC(19, 4) DEFAULT 0,
    mdr_amount      NUMERIC(19, 4) DEFAULT 0,
    gst_amount      NUMERIC(19, 4) DEFAULT 0,
    net_amount      NUMERIC(19, 4) DEFAULT 0,
    payment_count   INTEGER DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_settlement_records_batch ON settlement_records(batch_id);
CREATE INDEX idx_settlement_records_merchant ON settlement_records(merchant_id);
