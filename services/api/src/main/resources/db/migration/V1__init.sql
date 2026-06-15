CREATE TABLE IF NOT EXISTS organizations (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS accounts (
    id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    username VARCHAR(80) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at BIGINT NOT NULL,
    UNIQUE (organization_id, username)
);

CREATE TABLE IF NOT EXISTS rooms (
    id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    name VARCHAR(120) NOT NULL,
    platform VARCHAR(32) NOT NULL,
    external_room_id VARCHAR(120),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at BIGINT NOT NULL,
    UNIQUE (organization_id, name)
);

CREATE TABLE IF NOT EXISTS account_room_scopes (
    account_id VARCHAR(36) NOT NULL REFERENCES accounts(id),
    room_id VARCHAR(36) NOT NULL REFERENCES rooms(id),
    PRIMARY KEY (account_id, room_id)
);

CREATE TABLE IF NOT EXISTS shifts (
    id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    room_id VARCHAR(36) NOT NULL REFERENCES rooms(id),
    host_account_id VARCHAR(36) REFERENCES accounts(id),
    title VARCHAR(160) NOT NULL,
    start_at BIGINT NOT NULL,
    end_at BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    host_fixed_cents BIGINT NOT NULL DEFAULT 0,
    host_hourly_cents BIGINT NOT NULL DEFAULT 0,
    checked_in_at BIGINT,
    created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_shifts_org_room_time ON shifts(organization_id, room_id, start_at, end_at);

CREATE TABLE IF NOT EXISTS customers (
    id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    display_name VARCHAR(120) NOT NULL,
    relationship_stage VARCHAR(32) NOT NULL,
    contact_eligibility VARCHAR(32) NOT NULL,
    last_interaction_at BIGINT,
    next_follow_up_at BIGINT,
    do_not_contact_reason VARCHAR(300),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS customer_platform_accounts (
    id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    customer_id VARCHAR(36) NOT NULL REFERENCES customers(id),
    platform VARCHAR(32) NOT NULL,
    external_user_id VARCHAR(160) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    UNIQUE (organization_id, platform, external_user_id)
);

CREATE TABLE IF NOT EXISTS interaction_events (
    id VARCHAR(80) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    room_id VARCHAR(36) REFERENCES rooms(id),
    customer_id VARCHAR(36) REFERENCES customers(id),
    type VARCHAR(32) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    occurred_at BIGINT NOT NULL,
    source VARCHAR(32) NOT NULL
);

CREATE TABLE IF NOT EXISTS tasks (
    id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    room_id VARCHAR(36) REFERENCES rooms(id),
    customer_id VARCHAR(36) NOT NULL REFERENCES customers(id),
    title VARCHAR(160) NOT NULL,
    brief VARCHAR(1200) NOT NULL,
    state VARCHAR(24) NOT NULL,
    priority INTEGER NOT NULL DEFAULT 0,
    assigned_account_id VARCHAR(36) REFERENCES accounts(id),
    claimed_at BIGINT,
    started_at BIGINT,
    submitted_at BIGINT,
    reviewed_at BIGINT,
    result_channel VARCHAR(40),
    result_note VARCHAR(1200),
    next_follow_up_at BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tasks_org_state ON tasks(organization_id, state);

CREATE TABLE IF NOT EXISTS revenue_imports (
    id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    file_name VARCHAR(255) NOT NULL,
    file_sha256 VARCHAR(64) NOT NULL,
    expected_total_cents BIGINT NOT NULL,
    calculated_total_cents BIGINT NOT NULL,
    row_count INTEGER NOT NULL,
    duplicate_count INTEGER NOT NULL,
    state VARCHAR(24) NOT NULL,
    created_by VARCHAR(36) NOT NULL REFERENCES accounts(id),
    created_at BIGINT NOT NULL,
    committed_at BIGINT,
    UNIQUE (organization_id, file_sha256)
);

CREATE TABLE IF NOT EXISTS revenue_import_rows (
    id VARCHAR(80) PRIMARY KEY,
    import_id VARCHAR(36) NOT NULL REFERENCES revenue_imports(id),
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    room_id VARCHAR(36) NOT NULL REFERENCES rooms(id),
    platform VARCHAR(32) NOT NULL,
    transaction_id VARCHAR(160),
    row_fingerprint VARCHAR(64) NOT NULL,
    customer_external_id VARCHAR(160),
    customer_display_name VARCHAR(120),
    gift_category VARCHAR(120),
    gross_cents BIGINT NOT NULL,
    occurred_at BIGINT NOT NULL,
    duplicate BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS revenue_lines (
    id VARCHAR(80) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    import_id VARCHAR(36) NOT NULL REFERENCES revenue_imports(id),
    room_id VARCHAR(36) NOT NULL REFERENCES rooms(id),
    customer_id VARCHAR(36) REFERENCES customers(id),
    platform VARCHAR(32) NOT NULL,
    transaction_id VARCHAR(160),
    row_fingerprint VARCHAR(64) NOT NULL,
    gift_category VARCHAR(120),
    gross_cents BIGINT NOT NULL,
    occurred_at BIGINT NOT NULL,
    UNIQUE (organization_id, platform, row_fingerprint)
);
CREATE INDEX IF NOT EXISTS idx_revenue_org_time ON revenue_lines(organization_id, occurred_at);

CREATE TABLE IF NOT EXISTS settlement_rules (
    id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    name VARCHAR(120) NOT NULL,
    effective_from BIGINT NOT NULL,
    platform_rate_bps INTEGER NOT NULL,
    organization_share_bps INTEGER NOT NULL,
    member_commission_bps INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS expenses (
    id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    room_id VARCHAR(36) REFERENCES rooms(id),
    category VARCHAR(80) NOT NULL,
    amount_cents BIGINT NOT NULL,
    note VARCHAR(500) NOT NULL,
    occurred_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS settlements (
    id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    room_id VARCHAR(36) REFERENCES rooms(id),
    period_start BIGINT NOT NULL,
    period_end BIGINT NOT NULL,
    rule_id VARCHAR(36) NOT NULL REFERENCES settlement_rules(id),
    gross_cents BIGINT NOT NULL,
    platform_deduction_cents BIGINT NOT NULL,
    organization_share_cents BIGINT NOT NULL,
    member_commission_cents BIGINT NOT NULL,
    host_cost_cents BIGINT NOT NULL,
    expense_cents BIGINT NOT NULL,
    accounts_receivable_cents BIGINT NOT NULL,
    accounts_payable_cents BIGINT NOT NULL,
    net_profit_cents BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    closed_by VARCHAR(36) REFERENCES accounts(id),
    closed_at BIGINT,
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS settlement_adjustments (
    id VARCHAR(36) PRIMARY KEY,
    settlement_id VARCHAR(36) NOT NULL REFERENCES settlements(id),
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    amount_cents BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) NOT NULL REFERENCES accounts(id),
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
    id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    room_id VARCHAR(36) NOT NULL REFERENCES rooms(id),
    name VARCHAR(120) NOT NULL,
    secret_ciphertext VARCHAR(500) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at BIGINT,
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS device_events (
    event_id VARCHAR(120) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    room_id VARCHAR(36) NOT NULL REFERENCES rooms(id),
    device_id VARCHAR(36) NOT NULL REFERENCES devices(id),
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    occurred_at BIGINT NOT NULL,
    received_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    account_id VARCHAR(36),
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id VARCHAR(120),
    summary VARCHAR(800) NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_org_time ON audit_logs(organization_id, created_at);

CREATE TABLE IF NOT EXISTS value_level_rules (
    id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    code VARCHAR(40) NOT NULL,
    label VARCHAR(80) NOT NULL,
    minimum_30d_cents BIGINT NOT NULL,
    minimum_lifetime_cents BIGINT NOT NULL,
    sort_order INTEGER NOT NULL,
    UNIQUE (organization_id, code)
);

CREATE TABLE IF NOT EXISTS legacy_import_batches (
    id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    source VARCHAR(80) NOT NULL,
    source_version VARCHAR(40) NOT NULL,
    record_count INTEGER NOT NULL,
    payload_json TEXT NOT NULL,
    state VARCHAR(24) NOT NULL,
    created_by VARCHAR(36) NOT NULL REFERENCES accounts(id),
    created_at BIGINT NOT NULL
);
