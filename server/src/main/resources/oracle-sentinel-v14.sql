ALTER TABLE sentinel_action_dispatch_log ADD action_id_at_attempt NUMBER(10)

ALTER TABLE sentinel_action_dispatch_log ADD action_name_at_attempt VARCHAR2(255)

ALTER TABLE sentinel_action_dispatch_log ADD action_type_at_attempt VARCHAR2(20)

ALTER TABLE sentinel_action_dispatch_log ADD event_phase_at_attempt VARCHAR2(20)

CREATE INDEX idx_sentinel_dispatch_time ON sentinel_action_dispatch_log (dispatch_time, id)
