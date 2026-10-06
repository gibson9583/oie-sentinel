CREATE TABLE sentinel_maintenance_request (
    request_id VARCHAR(36) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    channel_id VARCHAR(36) NOT NULL,
    window_id INT,
    PRIMARY KEY (request_id)
)
