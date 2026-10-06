CREATE TABLE sentinel_maintenance_request (
    request_id VARCHAR2(36) NOT NULL,
    fingerprint VARCHAR2(64) NOT NULL,
    channel_id VARCHAR2(36) NOT NULL,
    window_id NUMBER(10),
    PRIMARY KEY (request_id)
)
