ALTER TABLE sentinel_connector_status_event ADD node_id VARCHAR(128) NOT NULL CONSTRAINT df_sentinel_connector_node_id DEFAULT 'legacy'
