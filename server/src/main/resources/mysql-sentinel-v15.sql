CREATE TABLE sentinel_job_observation (
    job_name VARCHAR(32) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    lease_epoch BIGINT NOT NULL,
    started_time DATETIME(3) NOT NULL,
    finished_time DATETIME(3),
    outcome VARCHAR(16) NOT NULL,
    last_success_time DATETIME(3),
    last_success_node VARCHAR(128),
    last_success_epoch BIGINT,
    CONSTRAINT pk_sentinel_job_observation PRIMARY KEY (job_name)
)
