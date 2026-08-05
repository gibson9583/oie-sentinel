ALTER TABLE sentinel_maintenance_window ADD window_mode VARCHAR(16) NOT NULL DEFAULT 'SUPPRESS'

ALTER TABLE sentinel_maintenance_window ADD repeat_type VARCHAR(16) NOT NULL DEFAULT 'NONE'

ALTER TABLE sentinel_maintenance_window ADD days_of_week VARCHAR(64)

ALTER TABLE sentinel_maintenance_window ADD days_of_month VARCHAR(128)

ALTER TABLE sentinel_maintenance_window ADD start_time VARCHAR(5)

ALTER TABLE sentinel_maintenance_window ADD end_time VARCHAR(5)

ALTER TABLE sentinel_maintenance_window ALTER COLUMN active_from DATETIME NULL

ALTER TABLE sentinel_maintenance_window ALTER COLUMN active_until DATETIME NULL
