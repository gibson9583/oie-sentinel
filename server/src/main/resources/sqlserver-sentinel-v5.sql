IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sentinel_activity_sample_time')
CREATE INDEX idx_sentinel_activity_sample_time ON sentinel_channel_activity_sample (sample_time)

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sentinel_activity_trend_hour')
CREATE INDEX idx_sentinel_activity_trend_hour ON sentinel_channel_activity_trend (hour_bucket)

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_sentinel_alert_event_resolved')
CREATE INDEX idx_sentinel_alert_event_resolved ON sentinel_alert_event (status, resolved_time)
