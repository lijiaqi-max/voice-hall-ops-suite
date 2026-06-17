ALTER TABLE devices ADD COLUMN IF NOT EXISTS role VARCHAR(32) NOT NULL DEFAULT 'collector';
ALTER TABLE devices ADD COLUMN IF NOT EXISTS wechat_group_reply_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS ingkee_voice_room_capture_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS wechat_calibration_status VARCHAR(80) NOT NULL DEFAULT '待校准';
ALTER TABLE devices ADD COLUMN IF NOT EXISTS ingkee_calibration_status VARCHAR(80) NOT NULL DEFAULT '待校准';
ALTER TABLE devices ADD COLUMN IF NOT EXISTS calibrated_at BIGINT;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS calibration_summary VARCHAR(500);

CREATE TABLE IF NOT EXISTS device_registration_tokens (
    id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    room_id VARCHAR(36) NOT NULL REFERENCES rooms(id),
    role VARCHAR(32) NOT NULL,
    token_sha256 VARCHAR(64) NOT NULL UNIQUE,
    expires_at BIGINT NOT NULL,
    used_at BIGINT,
    created_by VARCHAR(36) NOT NULL REFERENCES accounts(id),
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_device_registration_tokens_org_room
    ON device_registration_tokens(organization_id, room_id);

CREATE INDEX IF NOT EXISTS idx_devices_org_room_role
    ON devices(organization_id, room_id, role);
