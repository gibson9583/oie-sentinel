CREATE TABLE sentinel_match_sample (
    id            BIGINT IDENTITY(1,1) PRIMARY KEY,
    node_id       VARCHAR(128) NOT NULL,
    monitor_id    INTEGER NOT NULL FOREIGN KEY REFERENCES sentinel_monitor(id) ON DELETE CASCADE,
    channel_id    CHAR(36),
    sample_time   DATETIME2(3) NOT NULL,
    match_count   BIGINT NOT NULL,
    samples_json  NVARCHAR(MAX),
    complete      BIT NOT NULL DEFAULT 1
)
