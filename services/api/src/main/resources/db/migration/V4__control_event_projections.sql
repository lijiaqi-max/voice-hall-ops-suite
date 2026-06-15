CREATE TABLE member_platform_bindings (
    id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    room_id VARCHAR(36) NOT NULL REFERENCES rooms(id),
    external_binding_id VARCHAR(80) NOT NULL,
    wechat_name VARCHAR(120) NOT NULL,
    ingkee_name VARCHAR(120) NOT NULL,
    state VARCHAR(24) NOT NULL,
    approved_by_name VARCHAR(120),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE (organization_id, room_id, external_binding_id)
);
CREATE INDEX idx_bindings_room_ingkee
    ON member_platform_bindings(organization_id, room_id, ingkee_name, state);

CREATE TABLE queue_entries (
    id VARCHAR(80) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    room_id VARCHAR(36) NOT NULL REFERENCES rooms(id),
    shift_id VARCHAR(36) NOT NULL REFERENCES shifts(id),
    binding_id VARCHAR(36) REFERENCES member_platform_bindings(id),
    wechat_name VARCHAR(120) NOT NULL,
    role VARCHAR(24) NOT NULL,
    position INTEGER NOT NULL,
    state VARCHAR(24) NOT NULL,
    created_by VARCHAR(120) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE INDEX idx_queue_room_shift ON queue_entries(organization_id, room_id, shift_id, state, position);

CREATE TABLE mic_segments (
    id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    room_id VARCHAR(36) NOT NULL REFERENCES rooms(id),
    shift_id VARCHAR(36) REFERENCES shifts(id),
    binding_id VARCHAR(36) REFERENCES member_platform_bindings(id),
    wechat_name VARCHAR(120),
    ingkee_name VARCHAR(120) NOT NULL,
    role VARCHAR(24) NOT NULL,
    queue_position INTEGER,
    started_at BIGINT NOT NULL,
    last_seen_at BIGINT NOT NULL,
    ended_at BIGINT,
    duration_seconds BIGINT NOT NULL DEFAULT 0,
    state VARCHAR(24) NOT NULL,
    correction_reason VARCHAR(500),
    source_device_id VARCHAR(36) NOT NULL REFERENCES devices(id),
    updated_at BIGINT NOT NULL
);
CREATE INDEX idx_mic_segments_room_time ON mic_segments(organization_id, room_id, started_at);
CREATE INDEX idx_mic_segments_shift ON mic_segments(shift_id, state);

CREATE TABLE active_mic_presence (
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    room_id VARCHAR(36) NOT NULL REFERENCES rooms(id),
    device_id VARCHAR(36) NOT NULL REFERENCES devices(id),
    ingkee_name VARCHAR(120) NOT NULL,
    segment_id VARCHAR(36) NOT NULL REFERENCES mic_segments(id),
    last_seen_at BIGINT NOT NULL,
    missing_since BIGINT,
    PRIMARY KEY (organization_id, room_id, device_id, ingkee_name)
);

CREATE TABLE attendance (
    id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
    room_id VARCHAR(36) NOT NULL REFERENCES rooms(id),
    shift_id VARCHAR(36) NOT NULL REFERENCES shifts(id),
    identity_key VARCHAR(200) NOT NULL,
    binding_id VARCHAR(36) REFERENCES member_platform_bindings(id),
    wechat_name VARCHAR(120),
    ingkee_name VARCHAR(120) NOT NULL,
    first_seen_at BIGINT NOT NULL,
    last_seen_at BIGINT NOT NULL,
    ordinary_seconds BIGINT NOT NULL DEFAULT 0,
    host_seconds BIGINT NOT NULL DEFAULT 0,
    segment_count INTEGER NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL,
    UNIQUE (organization_id, room_id, shift_id, identity_key)
);
