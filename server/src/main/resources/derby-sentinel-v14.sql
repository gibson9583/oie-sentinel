CREATE TABLE sentinel_match_sample (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    node_id       VARCHAR(128) NOT NULL,
    monitor_id    INTEGER NOT NULL REFERENCES sentinel_monitor(id) ON DELETE CASCADE,
    channel_id    CHAR(36),
    sample_time   TIMESTAMP NOT NULL,
    match_count   BIGINT NOT NULL,
    samples_json  CLOB,
    complete      BOOLEAN NOT NULL DEFAULT true
)
