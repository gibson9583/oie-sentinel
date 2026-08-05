ALTER TABLE sentinel_maintenance_window ADD COLUMN window_mode VARCHAR(16) DEFAULT 'SUPPRESS' NOT NULL

ALTER TABLE sentinel_maintenance_window ADD COLUMN repeat_type VARCHAR(16) DEFAULT 'NONE' NOT NULL

ALTER TABLE sentinel_maintenance_window ADD COLUMN days_of_week VARCHAR(64)

ALTER TABLE sentinel_maintenance_window ADD COLUMN days_of_month VARCHAR(128)

ALTER TABLE sentinel_maintenance_window ADD COLUMN start_time VARCHAR(5)

ALTER TABLE sentinel_maintenance_window ADD COLUMN end_time VARCHAR(5)

ALTER TABLE sentinel_maintenance_window ALTER COLUMN active_from NULL

ALTER TABLE sentinel_maintenance_window ALTER COLUMN active_until NULL
