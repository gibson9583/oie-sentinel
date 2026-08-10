IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sentinel_connector_status_time')
CREATE INDEX idx_sentinel_connector_status_time ON sentinel_connector_status_event (changed_time)
