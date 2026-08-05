CREATE TABLE IF NOT EXISTS sentinel_monitor (
    id                        INTEGER PRIMARY KEY AUTO_INCREMENT,
    name                      VARCHAR(255) NOT NULL UNIQUE,
    description               VARCHAR(1024),
    monitor_type              VARCHAR(32) NOT NULL,
    scope_type                VARCHAR(16) NOT NULL,
    scope_id                  CHAR(36),
    enabled                   BOOLEAN NOT NULL DEFAULT true,
    severity                  VARCHAR(16) NOT NULL DEFAULT 'WARNING',
    config_json               LONGTEXT NOT NULL,
    min_consecutive_breaches  INTEGER NOT NULL DEFAULT 1,
    suppressed_by_monitor_id  INTEGER,
    created_by                INTEGER,
    created_time              DATETIME NOT NULL,
    updated_by                INTEGER,
    updated_time              DATETIME NULL DEFAULT NULL,
    FOREIGN KEY (suppressed_by_monitor_id) REFERENCES sentinel_monitor(id) ON DELETE SET NULL,
    INDEX idx_sentinel_monitor_scope (scope_type, scope_id),
    INDEX idx_sentinel_monitor_enabled (enabled)
);

CREATE TABLE IF NOT EXISTS sentinel_alert_event (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    monitor_id          INTEGER NOT NULL,
    channel_id          CHAR(36) NOT NULL,
    metadata_id         INTEGER,
    severity            VARCHAR(16) NOT NULL,
    status              VARCHAR(16) NOT NULL,
    message             VARCHAR(1024) NOT NULL,
    opened_time         DATETIME NOT NULL,
    resolved_time       DATETIME NULL DEFAULT NULL,
    acknowledged_by     INTEGER,
    acknowledged_time   DATETIME NULL DEFAULT NULL,
    ack_comment         VARCHAR(1024),
    details_json        LONGTEXT,
    suppressed          BOOLEAN NOT NULL DEFAULT false,
    FOREIGN KEY (monitor_id) REFERENCES sentinel_monitor(id) ON DELETE CASCADE,
    INDEX idx_sentinel_alert_event_monitor_channel (monitor_id, channel_id),
    INDEX idx_sentinel_alert_event_status (status),
    INDEX idx_sentinel_alert_event_opened (opened_time)
);

CREATE TABLE IF NOT EXISTS sentinel_trigger_state (
    id                          INTEGER PRIMARY KEY AUTO_INCREMENT,
    monitor_id                  INTEGER NOT NULL,
    channel_id                  CHAR(36) NOT NULL,
    metadata_id                 INTEGER,
    state                       VARCHAR(20) NOT NULL DEFAULT 'INSUFFICIENT_DATA',
    consecutive_breach_count    INTEGER NOT NULL DEFAULT 0,
    last_value_json             LONGTEXT,
    open_alert_event_id         BIGINT,
    last_change_time            DATETIME NULL DEFAULT NULL,
    last_evaluated_time         DATETIME NULL DEFAULT NULL,
    FOREIGN KEY (monitor_id) REFERENCES sentinel_monitor(id) ON DELETE CASCADE,
    UNIQUE (monitor_id, channel_id, metadata_id),
    INDEX idx_sentinel_trigger_state_monitor (monitor_id)
);

ALTER TABLE sentinel_trigger_state
    ADD CONSTRAINT fk_sentinel_trigger_state_alert_event
    FOREIGN KEY (open_alert_event_id) REFERENCES sentinel_alert_event(id) ON DELETE SET NULL;

CREATE TABLE IF NOT EXISTS sentinel_channel_activity_sample (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    channel_id       CHAR(36) NOT NULL,
    sample_time      DATETIME NOT NULL,
    received_delta   BIGINT NOT NULL DEFAULT 0,
    sent_delta       BIGINT NOT NULL DEFAULT 0,
    error_delta      BIGINT NOT NULL DEFAULT 0,
    filtered_delta   BIGINT NOT NULL DEFAULT 0,
    queued_snapshot  BIGINT NOT NULL DEFAULT 0,
    INDEX idx_sentinel_activity_sample_channel_time (channel_id, sample_time)
);

CREATE TABLE IF NOT EXISTS sentinel_channel_activity_trend (
    id             INTEGER PRIMARY KEY AUTO_INCREMENT,
    channel_id     CHAR(36) NOT NULL,
    hour_bucket    DATETIME NOT NULL,
    received_sum   BIGINT NOT NULL DEFAULT 0,
    sent_sum       BIGINT NOT NULL DEFAULT 0,
    error_sum      BIGINT NOT NULL DEFAULT 0,
    avg_queued     DOUBLE NOT NULL DEFAULT 0,
    min_queued     BIGINT NOT NULL DEFAULT 0,
    max_queued     BIGINT NOT NULL DEFAULT 0,
    UNIQUE (channel_id, hour_bucket),
    INDEX idx_sentinel_activity_trend_channel_hour (channel_id, hour_bucket)
);

CREATE TABLE IF NOT EXISTS sentinel_connector_status_event (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    channel_id       CHAR(36) NOT NULL,
    metadata_id      INTEGER NOT NULL,
    previous_state   VARCHAR(24),
    new_state        VARCHAR(24) NOT NULL,
    changed_time     DATETIME NOT NULL,
    INDEX idx_sentinel_connector_status_event_channel (channel_id, metadata_id, changed_time)
);

CREATE TABLE IF NOT EXISTS sentinel_action (
    id                        INTEGER PRIMARY KEY AUTO_INCREMENT,
    name                      VARCHAR(255) NOT NULL UNIQUE,
    description               VARCHAR(1024),
    enabled                   BOOLEAN NOT NULL DEFAULT true,
    action_type               VARCHAR(16) NOT NULL,
    condition_json            LONGTEXT NOT NULL,
    operation_mode            VARCHAR(16) NOT NULL DEFAULT 'ON_PROBLEM',
    repeat_interval_seconds   INTEGER,
    max_repeats               INTEGER,
    config_json               LONGTEXT NOT NULL,
    created_by                INTEGER,
    created_time              DATETIME NOT NULL,
    updated_by                INTEGER,
    updated_time              DATETIME NULL DEFAULT NULL,
    INDEX idx_sentinel_action_enabled (enabled)
);

CREATE TABLE IF NOT EXISTS sentinel_action_dispatch_log (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    alert_event_id   BIGINT NOT NULL,
    action_id        INTEGER,
    dispatch_time    DATETIME NOT NULL,
    success          BOOLEAN NOT NULL,
    error_message    VARCHAR(1024),
    FOREIGN KEY (alert_event_id) REFERENCES sentinel_alert_event(id) ON DELETE CASCADE,
    FOREIGN KEY (action_id) REFERENCES sentinel_action(id) ON DELETE SET NULL,
    INDEX idx_sentinel_action_dispatch_log_event (alert_event_id)
);

CREATE TABLE IF NOT EXISTS sentinel_maintenance_window (
    id             INTEGER PRIMARY KEY AUTO_INCREMENT,
    name           VARCHAR(255) NOT NULL,
    scope_type     VARCHAR(16) NOT NULL,
    scope_id       CHAR(36),
    active_from    DATETIME NOT NULL,
    active_until   DATETIME NOT NULL,
    enabled        BOOLEAN NOT NULL DEFAULT true,
    created_by     INTEGER,
    created_time   DATETIME NOT NULL,
    INDEX idx_sentinel_maintenance_window_scope (scope_type, scope_id)
);
