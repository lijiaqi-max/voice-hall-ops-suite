ALTER TABLE settlement_rules ADD COLUMN IF NOT EXISTS room_id VARCHAR(36);
ALTER TABLE settlement_rules ADD COLUMN IF NOT EXISTS role VARCHAR(32) NOT NULL DEFAULT 'all';
CREATE INDEX IF NOT EXISTS idx_settlement_rules_scope
    ON settlement_rules(organization_id, room_id, role, effective_from);

ALTER TABLE settlements ADD COLUMN IF NOT EXISTS base_accounts_receivable_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE settlements ADD COLUMN IF NOT EXISTS base_accounts_payable_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE settlements ADD COLUMN IF NOT EXISTS base_net_profit_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE settlements ADD COLUMN IF NOT EXISTS adjustment_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE settlements ADD COLUMN IF NOT EXISTS host_cost_estimated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE settlements ADD COLUMN IF NOT EXISTS host_cost_override_reason VARCHAR(500);
ALTER TABLE settlements ADD COLUMN IF NOT EXISTS unallocated_revenue_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE settlements ADD COLUMN IF NOT EXISTS reconciliation_difference_cents BIGINT NOT NULL DEFAULT 0;

UPDATE settlements
SET base_accounts_receivable_cents=accounts_receivable_cents,
    base_accounts_payable_cents=accounts_payable_cents,
    base_net_profit_cents=net_profit_cents
WHERE base_accounts_receivable_cents=0
  AND base_accounts_payable_cents=0
  AND base_net_profit_cents=0;

ALTER TABLE settlement_adjustments ADD COLUMN IF NOT EXISTS effect VARCHAR(24) NOT NULL DEFAULT 'net';
ALTER TABLE settlement_adjustments ADD COLUMN IF NOT EXISTS before_receivable_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE settlement_adjustments ADD COLUMN IF NOT EXISTS before_payable_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE settlement_adjustments ADD COLUMN IF NOT EXISTS before_net_profit_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE settlement_adjustments ADD COLUMN IF NOT EXISTS after_receivable_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE settlement_adjustments ADD COLUMN IF NOT EXISTS after_payable_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE settlement_adjustments ADD COLUMN IF NOT EXISTS after_net_profit_cents BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS settlement_lines (
    id VARCHAR(36) PRIMARY KEY,
    settlement_id VARCHAR(36) NOT NULL REFERENCES settlements(id),
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    room_id VARCHAR(36) REFERENCES rooms(id),
    line_type VARCHAR(32) NOT NULL,
    reference_id VARCHAR(80),
    account_id VARCHAR(36) REFERENCES accounts(id),
    label VARCHAR(200) NOT NULL,
    gross_cents BIGINT NOT NULL DEFAULT 0,
    amount_cents BIGINT NOT NULL,
    verified BOOLEAN NOT NULL DEFAULT TRUE,
    note VARCHAR(500),
    created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_settlement_lines_settlement
    ON settlement_lines(settlement_id, line_type);
