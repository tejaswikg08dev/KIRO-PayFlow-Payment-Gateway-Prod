-- V4: Create fee_configs table
CREATE TABLE fee_configs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID NOT NULL UNIQUE,
    mdr_percent     DECIMAL(5, 2) NOT NULL,
    gst_percent     DECIMAL(5, 2) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_fee_configs_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants(id) ON DELETE CASCADE
);

CREATE INDEX idx_fee_configs_merchant_id ON fee_configs(merchant_id);
