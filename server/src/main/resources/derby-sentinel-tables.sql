CREATE TABLE sentinel_monitor (
    id                        INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                      VARCHAR(255) NOT NULL UNIQUE,
    description               VARCHAR(1024),
    monitor_type              VARCHAR(32) NOT NULL,
    scope_type                VARCHAR(16) NOT NULL,
    scope_id                  CHAR(36),
    enabled                   BOOLEAN NOT NULL DEFAULT true,
    severity                  VARCHAR(16) NOT NULL DEFAULT 'WARNING',
    config_json               CLOB NOT NULL,
    min_consecutive_breaches  INTEGER NOT NULL DEFAULT 1,
    suppressed_by_monitor_id  INTEGER,
    created_by                INTEGER,
    created_time              TIMESTAMP NOT NULL,
    updated_by                INTEGER,
    updated_time              TIMESTAMP
)

ALTER TABLE sentinel_monitor
    ADD CONSTRAINT fk_sentinel_monitor_suppressed_by
    FOREIGN KEY (suppressed_by_monitor_id) REFERENCES sentinel_monitor(id) ON DELETE SET NULL

CREATE INDEX idx_sentinel_monitor_scope ON sentinel_monitor (scope_type, scope_id)

CREATE INDEX idx_sentinel_monitor_enabled ON sentinel_monitor (enabled)

CREATE TABLE sentinel_alert_event (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    monitor_id          INTEGER NOT NULL REFERENCES sentinel_monitor(id) ON DELETE CASCADE,
    channel_id          CHAR(36) NOT NULL,
    metadata_id         INTEGER,
    severity            VARCHAR(16) NOT NULL,
    status              VARCHAR(16) NOT NULL,
    message             VARCHAR(1024) NOT NULL,
    opened_time         TIMESTAMP NOT NULL,
    resolved_time       TIMESTAMP,
    acknowledged_by     INTEGER,
    acknowledged_time   TIMESTAMP,
    ack_comment         VARCHAR(1024),
    details_json        CLOB,
    suppressed          BOOLEAN NOT NULL DEFAULT false
)

CREATE INDEX idx_sentinel_alert_event_monitor_channel ON sentinel_alert_event (monitor_id, channel_id)

CREATE INDEX idx_sentinel_alert_event_status ON sentinel_alert_event (status)

CREATE INDEX idx_sentinel_alert_event_opened ON sentinel_alert_event (opened_time)

CREATE TABLE sentinel_trigger_state (
    id                          INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    monitor_id                  INTEGER NOT NULL REFERENCES sentinel_monitor(id) ON DELETE CASCADE,
    channel_id                  CHAR(36) NOT NULL,
    metadata_id                 INTEGER,
    state                       VARCHAR(20) NOT NULL DEFAULT 'INSUFFICIENT_DATA',
    consecutive_breach_count    INTEGER NOT NULL DEFAULT 0,
    last_value_json             CLOB,
    open_alert_event_id         BIGINT,
    last_change_time            TIMESTAMP,
    last_evaluated_time         TIMESTAMP,
    UNIQUE (monitor_id, channel_id, metadata_id)
)

CREATE INDEX idx_sentinel_trigger_state_monitor ON sentinel_trigger_state (monitor_id)

CREATE TABLE sentinel_channel_activity_sample (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel_id       CHAR(36) NOT NULL,
    sample_time      TIMESTAMP NOT NULL,
    received_delta   BIGINT NOT NULL DEFAULT 0,
    sent_delta       BIGINT NOT NULL DEFAULT 0,
    error_delta      BIGINT NOT NULL DEFAULT 0,
    filtered_delta   BIGINT NOT NULL DEFAULT 0,
    queued_snapshot  BIGINT NOT NULL DEFAULT 0
)

CREATE INDEX idx_sentinel_activity_sample_channel_time ON sentinel_channel_activity_sample (channel_id, sample_time)

CREATE TABLE sentinel_channel_activity_trend (
    id             INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel_id     CHAR(36) NOT NULL,
    hour_bucket    TIMESTAMP NOT NULL,
    received_sum   BIGINT NOT NULL DEFAULT 0,
    sent_sum       BIGINT NOT NULL DEFAULT 0,
    error_sum      BIGINT NOT NULL DEFAULT 0,
    avg_queued     DOUBLE PRECISION NOT NULL DEFAULT 0,
    min_queued     BIGINT NOT NULL DEFAULT 0,
    max_queued     BIGINT NOT NULL DEFAULT 0,
    UNIQUE (channel_id, hour_bucket)
)

CREATE INDEX idx_sentinel_activity_trend_channel_hour ON sentinel_channel_activity_trend (channel_id, hour_bucket)

CREATE TABLE sentinel_connector_status_event (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel_id       CHAR(36) NOT NULL,
    metadata_id      INTEGER NOT NULL,
    previous_state   VARCHAR(24),
    new_state        VARCHAR(24) NOT NULL,
    changed_time     TIMESTAMP NOT NULL
)

CREATE INDEX idx_sentinel_connector_status_event_channel ON sentinel_connector_status_event (channel_id, metadata_id, changed_time)

CREATE TABLE sentinel_action (
    id                        INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                      VARCHAR(255) NOT NULL UNIQUE,
    description               VARCHAR(1024),
    enabled                   BOOLEAN NOT NULL DEFAULT true,
    action_type               VARCHAR(16) NOT NULL,
    condition_json            CLOB NOT NULL,
    operation_mode            VARCHAR(16) NOT NULL DEFAULT 'ON_PROBLEM',
    repeat_interval_seconds   INTEGER,
    max_repeats               INTEGER,
    config_json               CLOB NOT NULL,
    created_by                INTEGER,
    created_time              TIMESTAMP NOT NULL,
    updated_by                INTEGER,
    updated_time              TIMESTAMP
)

CREATE INDEX idx_sentinel_action_enabled ON sentinel_action (enabled)

CREATE TABLE sentinel_action_dispatch_log (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_event_id   BIGINT NOT NULL REFERENCES sentinel_alert_event(id) ON DELETE CASCADE,
    action_id        INTEGER REFERENCES sentinel_action(id) ON DELETE SET NULL,
    dispatch_time    TIMESTAMP NOT NULL,
    success          BOOLEAN NOT NULL,
    error_message    VARCHAR(1024)
)

CREATE INDEX idx_sentinel_action_dispatch_log_event ON sentinel_action_dispatch_log (alert_event_id)

CREATE TABLE sentinel_maintenance_window (
    id             INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name           VARCHAR(255) NOT NULL,
    scope_type     VARCHAR(16) NOT NULL,
    scope_id       CHAR(36),
    active_from    TIMESTAMP NOT NULL,
    active_until   TIMESTAMP NOT NULL,
    enabled        BOOLEAN NOT NULL DEFAULT true,
    created_by     INTEGER,
    created_time   TIMESTAMP NOT NULL
)

CREATE INDEX idx_sentinel_maintenance_window_scope ON sentinel_maintenance_window (scope_type, scope_id)
