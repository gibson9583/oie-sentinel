ALTER TABLE sentinel_action_dispatch_log ADD COLUMN action_id_at_attempt INTEGER

ALTER TABLE sentinel_action_dispatch_log ADD COLUMN action_name_at_attempt VARCHAR(255)

ALTER TABLE sentinel_action_dispatch_log ADD COLUMN action_type_at_attempt VARCHAR(20)

ALTER TABLE sentinel_action_dispatch_log ADD COLUMN event_phase_at_attempt VARCHAR(20)

CREATE INDEX idx_sentinel_dispatch_time ON sentinel_action_dispatch_log (dispatch_time, id)
