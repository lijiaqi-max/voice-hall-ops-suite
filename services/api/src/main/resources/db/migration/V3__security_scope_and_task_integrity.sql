ALTER TABLE accounts ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;

CREATE TABLE refresh_tokens (
    id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    account_id VARCHAR(36) NOT NULL REFERENCES accounts(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at BIGINT NOT NULL,
    revoked_at BIGINT,
    created_at BIGINT NOT NULL
);
CREATE INDEX idx_refresh_tokens_account ON refresh_tokens(account_id, expires_at);

CREATE TABLE auth_login_attempts (
    login_key VARCHAR(160) PRIMARY KEY,
    failure_count INTEGER NOT NULL,
    window_started_at BIGINT NOT NULL,
    blocked_until BIGINT
);

ALTER TABLE tasks ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tasks ADD COLUMN active_key VARCHAR(16);

UPDATE tasks
SET active_key = 'active'
WHERE state IN ('published','claimed','assigned','in_progress','submitted');

CREATE UNIQUE INDEX uq_tasks_one_active_customer
    ON tasks(organization_id, customer_id, active_key);

CREATE TABLE task_operations (
    operation_id VARCHAR(120) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    account_id VARCHAR(36) NOT NULL REFERENCES accounts(id),
    task_id VARCHAR(36) NOT NULL REFERENCES tasks(id),
    action VARCHAR(32) NOT NULL,
    resulting_version INTEGER NOT NULL,
    created_at BIGINT NOT NULL
);

ALTER TABLE device_events ADD COLUMN processing_state VARCHAR(24) NOT NULL DEFAULT 'pending';
ALTER TABLE device_events ADD COLUMN processed_at BIGINT;
ALTER TABLE device_events ADD COLUMN processing_error VARCHAR(500);
