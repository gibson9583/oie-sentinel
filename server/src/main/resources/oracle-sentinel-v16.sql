CREATE TABLE sentinel_incident_note (
    note_id VARCHAR2(36) NOT NULL PRIMARY KEY,
    alert_event_id NUMBER(19) NOT NULL,
    actor_id INTEGER NOT NULL,
    created_time TIMESTAMP(3) NOT NULL,
    note_text VARCHAR2(4000 BYTE) NOT NULL
);
