-- V3: Create webhook_configs table
CREATE TABLE webhook_configs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    url             VARCHAR(2048) NOT NULL,
    secret          VARCHAR(255) NOT NULL,
    events          TEXT[] NOT NULL DEFAULT '{}',
    merchant_id     UUID NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_webhook_configs_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants(id) ON DELETE CASCADE
);

CREATE INDEX idx_webhook_configs_merchant_id ON webhook_configs(merchant_id);
CREATE INDEX idx_webhook_configs_active ON webhook_configs(active);
