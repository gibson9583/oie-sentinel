CREATE TABLE sentinel_channel_presence (
    channel_id CHAR(36) NOT NULL,
    node_id VARCHAR2(255) NOT NULL,
    metadata_id NUMBER(10) NOT NULL,
    deployed_time TIMESTAMP NOT NULL,
    PRIMARY KEY (node_id, channel_id, metadata_id)
)
