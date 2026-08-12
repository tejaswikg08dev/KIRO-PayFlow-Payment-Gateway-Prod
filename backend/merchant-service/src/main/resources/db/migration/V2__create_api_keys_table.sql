-- V2: Create api_keys table
CREATE TABLE api_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key_hash        VARCHAR(64) NOT NULL UNIQUE,
    prefix          VARCHAR(8) NOT NULL,
    merchant_id     UUID NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_api_keys_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants(id) ON DELETE CASCADE
);

CREATE INDEX idx_api_keys_key_hash ON api_keys(key_hash);
CREATE INDEX idx_api_keys_merchant_id ON api_keys(merchant_id);
CREATE INDEX idx_api_keys_prefix ON api_keys(prefix);
