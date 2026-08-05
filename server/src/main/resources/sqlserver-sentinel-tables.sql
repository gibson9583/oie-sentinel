IF NOT EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'sentinel_monitor') AND type in (N'U'))
CREATE TABLE sentinel_monitor (
    id                        INTEGER IDENTITY(1,1) PRIMARY KEY,
    name                      VARCHAR(255) NOT NULL UNIQUE,
    description               VARCHAR(1024),
    monitor_type              VARCHAR(32) NOT NULL,
    scope_type                VARCHAR(16) NOT NULL,
    scope_id                  CHAR(36),
    enabled                   BIT NOT NULL DEFAULT 1,
    severity                  VARCHAR(16) NOT NULL DEFAULT 'WARNING',
    config_json               NVARCHAR(MAX) NOT NULL,
    min_consecutive_breaches  INTEGER NOT NULL DEFAULT 1,
    suppressed_by_monitor_id  INTEGER FOREIGN KEY REFERENCES sentinel_monitor(id),
    created_by                INTEGER,
    created_time              DATETIME NOT NULL,
    updated_by                INTEGER,
    updated_time              DATETIME
)

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sentinel_monitor_scope')
CREATE INDEX idx_sentinel_monitor_scope ON sentinel_monitor (scope_type, scope_id)

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sentinel_monitor_enabled')
CREATE INDEX idx_sentinel_monitor_enabled ON sentinel_monitor (enabled)

IF NOT EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'sentinel_alert_event') AND type in (N'U'))
CREATE TABLE sentinel_alert_event (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    monitor_id          INTEGER NOT NULL FOREIGN KEY REFERENCES sentinel_monitor(id) ON DELETE CASCADE,
    channel_id          CHAR(36) NOT NULL,
    metadata_id         INTEGER,
    severity            VARCHAR(16) NOT NULL,
    status              VARCHAR(16) NOT NULL,
    message             VARCHAR(1024) NOT NULL,
    opened_time         DATETIME NOT NULL,
    resolved_time       DATETIME,
    acknowledged_by     INTEGER,
    acknowledged_time   DATETIME,
    ack_comment         VARCHAR(1024),
    details_json        NVARCHAR(MAX),
    suppressed          BIT NOT NULL DEFAULT 0
)

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sentinel_alert_event_monitor_channel')
CREATE INDEX idx_sentinel_alert_event_monitor_channel ON sentinel_alert_event (monitor_id, channel_id)

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sentinel_alert_event_status')
CREATE INDEX idx_sentinel_alert_event_status ON sentinel_alert_event (status)

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sentinel_alert_event_opened')
CREATE INDEX idx_sentinel_alert_event_opened ON sentinel_alert_event (opened_time)

IF NOT EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'sentinel_trigger_state') AND type in (N'U'))
CREATE TABLE sentinel_trigger_state (
    id                          INTEGER IDENTITY(1,1) PRIMARY KEY,
    monitor_id                  INTEGER NOT NULL FOREIGN KEY REFERENCES sentinel_monitor(id) ON DELETE CASCADE,
    channel_id                  CHAR(36) NOT NULL,
    metadata_id                 INTEGER,
    state                       VARCHAR(20) NOT NULL DEFAULT 'INSUFFICIENT_DATA',
    consecutive_breach_count    INTEGER NOT NULL DEFAULT 0,
    last_value_json             NVARCHAR(MAX),
    open_alert_event_id         BIGINT,
    last_change_time            DATETIME,
    last_evaluated_time         DATETIME,
    UNIQUE (monitor_id, channel_id, metadata_id)
)

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sentinel_trigger_state_monitor')
CREATE INDEX idx_sentinel_trigger_state_monitor ON sentinel_trigger_state (monitor_id)

IF NOT EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'sentinel_channel_activity_sample') AND type in (N'U'))
CREATE TABLE sentinel_channel_activity_sample (
    id               BIGINT IDENTITY(1,1) PRIMARY KEY,
    channel_id       CHAR(36) NOT NULL,
    sample_time      DATETIME NOT NULL,
    received_delta   BIGINT NOT NULL DEFAULT 0,
    sent_delta       BIGINT NOT NULL DEFAULT 0,
    error_delta      BIGINT NOT NULL DEFAULT 0,
    filtered_delta   BIGINT NOT NULL DEFAULT 0,
    queued_snapshot  BIGINT NOT NULL DEFAULT 0
)

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sentinel_activity_sample_channel_time')
CREATE INDEX idx_sentinel_activity_sample_channel_time ON sentinel_channel_activity_sample (channel_id, sample_time)

IF NOT EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'sentinel_channel_activity_trend') AND type in (N'U'))
CREATE TABLE sentinel_channel_activity_trend (
    id             INTEGER IDENTITY(1,1) PRIMARY KEY,
    channel_id     CHAR(36) NOT NULL,
    hour_bucket    DATETIME NOT NULL,
    received_sum   BIGINT NOT NULL DEFAULT 0,
    sent_sum       BIGINT NOT NULL DEFAULT 0,
    error_sum      BIGINT NOT NULL DEFAULT 0,
    avg_queued     FLOAT NOT NULL DEFAULT 0,
    min_queued     BIGINT NOT NULL DEFAULT 0,
    max_queued     BIGINT NOT NULL DEFAULT 0,
    UNIQUE (channel_id, hour_bucket)
)

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sentinel_activity_trend_channel_hour')
CREATE INDEX idx_sentinel_activity_trend_channel_hour ON sentinel_channel_activity_trend (channel_id, hour_bucket)

IF NOT EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'sentinel_connector_status_event') AND type in (N'U'))
CREATE TABLE sentinel_connector_status_event (
    id               BIGINT IDENTITY(1,1) PRIMARY KEY,
    channel_id       CHAR(36) NOT NULL,
    metadata_id      INTEGER NOT NULL,
    previous_state   VARCHAR(24),
    new_state        VARCHAR(24) NOT NULL,
    changed_time     DATETIME NOT NULL
)

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sentinel_connector_status_event_channel')
CREATE INDEX idx_sentinel_connector_status_event_channel ON sentinel_connector_status_event (channel_id, metadata_id, changed_time)

IF NOT EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'sentinel_action') AND type in (N'U'))
CREATE TABLE sentinel_action (
    id                        INTEGER IDENTITY(1,1) PRIMARY KEY,
    name                      VARCHAR(255) NOT NULL UNIQUE,
    description               VARCHAR(1024),
    enabled                   BIT NOT NULL DEFAULT 1,
    action_type               VARCHAR(16) NOT NULL,
    condition_json            NVARCHAR(MAX) NOT NULL,
    operation_mode            VARCHAR(16) NOT NULL DEFAULT 'ON_PROBLEM',
    repeat_interval_seconds   INTEGER,
    max_repeats               INTEGER,
    config_json               NVARCHAR(MAX) NOT NULL,
    created_by                INTEGER,
    created_time              DATETIME NOT NULL,
    updated_by                INTEGER,
    updated_time              DATETIME
)

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sentinel_action_enabled')
CREATE INDEX idx_sentinel_action_enabled ON sentinel_action (enabled)

IF NOT EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'sentinel_action_dispatch_log') AND type in (N'U'))
CREATE TABLE sentinel_action_dispatch_log (
    id               BIGINT IDENTITY(1,1) PRIMARY KEY,
    alert_event_id   BIGINT NOT NULL FOREIGN KEY REFERENCES sentinel_alert_event(id) ON DELETE CASCADE,
    action_id        INTEGER FOREIGN KEY REFERENCES sentinel_action(id) ON DELETE SET NULL,
    dispatch_time    DATETIME NOT NULL,
    success          BIT NOT NULL,
    error_message    VARCHAR(1024)
)

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sentinel_action_dispatch_log_event')
CREATE INDEX idx_sentinel_action_dispatch_log_event ON sentinel_action_dispatch_log (alert_event_id)

IF NOT EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'sentinel_maintenance_window') AND type in (N'U'))
CREATE TABLE sentinel_maintenance_window (
    id             INTEGER IDENTITY(1,1) PRIMARY KEY,
    name           VARCHAR(255) NOT NULL,
    scope_type     VARCHAR(16) NOT NULL,
    scope_id       CHAR(36),
    active_from    DATETIME NOT NULL,
    active_until   DATETIME NOT NULL,
    enabled        BIT NOT NULL DEFAULT 1,
    created_by     INTEGER,
    created_time   DATETIME NOT NULL
)

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sentinel_maintenance_window_scope')
CREATE INDEX idx_sentinel_maintenance_window_scope ON sentinel_maintenance_window (scope_type, scope_id)
