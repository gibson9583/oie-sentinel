ALTER TABLE sentinel_maintenance_window ADD window_mode VARCHAR2(16) DEFAULT 'SUPPRESS' NOT NULL

ALTER TABLE sentinel_maintenance_window ADD repeat_type VARCHAR2(16) DEFAULT 'NONE' NOT NULL

ALTER TABLE sentinel_maintenance_window ADD days_of_week VARCHAR2(64)

ALTER TABLE sentinel_maintenance_window ADD days_of_month VARCHAR2(128)

ALTER TABLE sentinel_maintenance_window ADD start_time VARCHAR2(5)

ALTER TABLE sentinel_maintenance_window ADD end_time VARCHAR2(5)

ALTER TABLE sentinel_maintenance_window MODIFY (active_from NULL)

ALTER TABLE sentinel_maintenance_window MODIFY (active_until NULL)
