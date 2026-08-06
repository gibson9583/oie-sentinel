ALTER TABLE sentinel_monitor ADD runbook_url VARCHAR2(1024)

ALTER TABLE sentinel_action ADD max_notifications_per_window NUMBER

ALTER TABLE sentinel_action ADD rollup_window_seconds NUMBER

ALTER TABLE sentinel_action ADD escalate_after_seconds NUMBER

ALTER TABLE sentinel_action ADD escalate_to_action_id NUMBER

CREATE TABLE sentinel_node_lease (
    lease_name      VARCHAR2(64) PRIMARY KEY,
    node_id         VARCHAR2(128) NOT NULL,
    acquired_time   TIMESTAMP NOT NULL,
    expires_time    TIMESTAMP NOT NULL
)
