ALTER TABLE sentinel_monitor ADD COLUMN runbook_url VARCHAR(1024)

ALTER TABLE sentinel_action ADD COLUMN max_notifications_per_window INTEGER

ALTER TABLE sentinel_action ADD COLUMN rollup_window_seconds INTEGER

ALTER TABLE sentinel_action ADD COLUMN escalate_after_seconds INTEGER

ALTER TABLE sentinel_action ADD COLUMN escalate_to_action_id INTEGER

CREATE TABLE IF NOT EXISTS sentinel_node_lease (
    lease_name      VARCHAR(64) PRIMARY KEY,
    node_id         VARCHAR(128) NOT NULL,
    acquired_time   DATETIME NOT NULL,
    expires_time    DATETIME NOT NULL
)
