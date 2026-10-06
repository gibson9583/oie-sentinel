CREATE TABLE sentinel_incident_note (
    note_id VARCHAR(36) NOT NULL PRIMARY KEY,
    alert_event_id BIGINT NOT NULL,
    actor_id INTEGER NOT NULL,
    created_time DATETIME2(3) NOT NULL,
    note_text NVARCHAR(4000) NOT NULL
);
