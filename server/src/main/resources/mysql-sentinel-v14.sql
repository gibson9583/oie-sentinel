CREATE TABLE sentinel_match_sample (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    node_id       VARCHAR(128) NOT NULL,
    monitor_id    INTEGER NOT NULL,
    channel_id    CHAR(36),
    sample_time   DATETIME(3) NOT NULL,
    match_count   BIGINT NOT NULL,
    samples_json  LONGTEXT,
    complete      BOOLEAN NOT NULL DEFAULT true,
    FOREIGN KEY (monitor_id) REFERENCES sentinel_monitor(id) ON DELETE CASCADE
)
