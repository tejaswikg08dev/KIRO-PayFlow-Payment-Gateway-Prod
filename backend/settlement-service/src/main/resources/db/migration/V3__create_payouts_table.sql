CREATE TABLE payouts (
    id                      VARCHAR(36) PRIMARY KEY,
    merchant_id             VARCHAR(36) NOT NULL,
    settlement_record_id    VARCHAR(36) NOT NULL REFERENCES settlement_records(id),
    amount                  NUMERIC(19, 4) NOT NULL,
    bank_account_number     VARCHAR(50),
    bank_ifsc               VARCHAR(20),
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payouts_merchant ON payouts(merchant_id);
CREATE INDEX idx_payouts_status ON payouts(status);
CREATE INDEX idx_payouts_settlement_record ON payouts(settlement_record_id);
