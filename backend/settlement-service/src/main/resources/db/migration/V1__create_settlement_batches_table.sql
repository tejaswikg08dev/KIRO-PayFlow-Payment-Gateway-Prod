CREATE TABLE settlement_batches (
    id              VARCHAR(36) PRIMARY KEY,
    settlement_date DATE NOT NULL,
    total_gross     NUMERIC(19, 4) DEFAULT 0,
    total_refunds   NUMERIC(19, 4) DEFAULT 0,
    total_mdr       NUMERIC(19, 4) DEFAULT 0,
    total_gst       NUMERIC(19, 4) DEFAULT 0,
    total_net       NUMERIC(19, 4) DEFAULT 0,
    record_count    INTEGER DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_settlement_batches_date ON settlement_batches(settlement_date);
CREATE INDEX idx_settlement_batches_status ON settlement_batches(status);
