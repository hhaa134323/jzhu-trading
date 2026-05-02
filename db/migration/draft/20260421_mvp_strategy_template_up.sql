CREATE TABLE IF NOT EXISTS strategy_template (
    template_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    owner_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS strategy_template_version (
    template_id VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    source_kind VARCHAR(20) NOT NULL,
    definition_json JSONB NOT NULL,
    change_note VARCHAR(256),
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (template_id, version_no),
    CONSTRAINT fk_template_version_template
        FOREIGN KEY (template_id)
        REFERENCES strategy_template (template_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_strategy_template_owner_status
    ON strategy_template (owner_id, status);

CREATE INDEX IF NOT EXISTS idx_strategy_template_version_created
    ON strategy_template_version (template_id, created_at DESC);
