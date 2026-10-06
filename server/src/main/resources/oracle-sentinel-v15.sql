CREATE TABLE sentinel_job_observation (
    job_name VARCHAR2(32) NOT NULL,
    run_id VARCHAR2(36) NOT NULL,
    node_id VARCHAR2(128) NOT NULL,
    lease_epoch NUMBER(19) NOT NULL,
    started_time TIMESTAMP NOT NULL,
    finished_time TIMESTAMP,
    outcome VARCHAR2(16) NOT NULL,
    last_success_time TIMESTAMP,
    last_success_node VARCHAR2(128),
    last_success_epoch NUMBER(19),
    CONSTRAINT pk_sentinel_job_observation PRIMARY KEY (job_name)
)
