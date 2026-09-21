ALTER TABLE sentinel_node_lease ADD lease_epoch BIGINT NOT NULL CONSTRAINT df_sentinel_node_lease_epoch DEFAULT 0
