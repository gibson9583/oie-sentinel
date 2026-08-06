ALTER TABLE sentinel_monitor ADD runbook_url VARCHAR(1024)

ALTER TABLE sentinel_action ADD max_notifications_per_window INTEGER

ALTER TABLE sentinel_action ADD rollup_window_seconds INTEGER

ALTER TABLE sentinel_action ADD escalate_after_seconds INTEGER

ALTER TABLE sentinel_action ADD escalate_to_action_id INTEGER

IF NOT EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'sentinel_node_lease') AND type in (N'U'))
CREATE TABLE sentinel_node_lease (
    lease_name      VARCHAR(64) PRIMARY KEY,
    node_id         VARCHAR(128) NOT NULL,
    acquired_time   DATETIME NOT NULL,
    expires_time    DATETIME NOT NULL
)
