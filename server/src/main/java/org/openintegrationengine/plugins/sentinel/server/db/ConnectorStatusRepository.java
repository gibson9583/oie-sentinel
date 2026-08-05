/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.server.util.SqlConfig;

import org.openintegrationengine.plugins.sentinel.shared.model.ConnectorStatusEvent;

/**
 * Persistence for {@code sentinel_connector_status_event} — the connector
 * connection-state transition history a {@code CONNECTION_STATUS} monitor
 * reads to detect connectors stuck in a failed or unexpected state.
 *
 * <p>Every statement here is a single round trip, so each method calls
 * {@code SqlConfig.getInstance().getSqlSessionManager()} directly rather than
 * opening a manual-commit session. This class holds no state of its own, so
 * like {@code MonitorRepository} and {@code TriggerStateRepository} it is a
 * plain collection of static methods rather than an
 * init()/getInstance()/close() singleton. The insert statement is a plain
 * insert with no generated-key retrieval — nothing here ever needs the id of
 * a freshly-written row back — so this repository has no use for the
 * toInteger/toBoolean coercion helpers found in the other repositories.</p>
 */
public final class ConnectorStatusRepository {

    private static final String NAMESPACE = "Sentinel";
    private static final Logger log = LoggerFactory.getLogger(ConnectorStatusRepository.class);

    private ConnectorStatusRepository() {
    }

    /** Qualifies a mapped-statement id with this plugin's MyBatis namespace. */
    private static String stmt(String id) {
        return NAMESPACE + "." + id;
    }

    /**
     * Records a connector connection-state transition.
     *
     * @param event the event to persist; its {@code id} field is ignored (the
     *              database assigns it, but this statement never retrieves
     *              the generated key back)
     * @throws RepositoryException on persistence failure
     */
    public static void insertConnectorStatusEvent(ConnectorStatusEvent event) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("channel_id", event.getChannelId());
            params.put("metadata_id", event.getMetadataId());
            params.put("previous_state", event.getPreviousState());
            params.put("new_state", event.getNewState());
            params.put("changed_time", toTimestamp(event.getChangedTime()));

            SqlConfig.getInstance().getSqlSessionManager().insert(stmt("insertConnectorStatusEvent"), params);
        } catch (Exception e) {
            log.error("Failed to insert connector status event for channel {}, metadata {}",
                    event.getChannelId(), event.getMetadataId(), e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Fetches the most recent connector status event for one connector.
     *
     * @param channelId  the OIE channel id (a UUID string)
     * @param metadataId the connector metadata id within the channel
     * @return the most recent event by {@code changed_time}, or {@code null}
     *         if this connector has never recorded one
     * @throws RepositoryException on persistence failure
     */
    public static ConnectorStatusEvent getLatestConnectorStatusEvent(String channelId, int metadataId) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("channelId", channelId);
            params.put("metadataId", metadataId);

            Map<String, Object> row = SqlConfig.getInstance().getSqlSessionManager()
                    .selectOne(stmt("getLatestConnectorStatusEvent"), params);

            return row != null ? toConnectorStatusEvent(row) : null;
        } catch (Exception e) {
            log.error("Failed to get latest connector status event for channel {}, metadata {}",
                    channelId, metadataId, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Lists connector status events for a channel within a time range,
     * ordered by {@code changed_time}.
     *
     * @param channelId the OIE channel id (a UUID string)
     * @param from      start of the range, inclusive
     * @param to        end of the range, inclusive
     * @return matching events ordered by {@code changed_time} ascending;
     *         never {@code null}
     * @throws RepositoryException on persistence failure
     */
    public static List<ConnectorStatusEvent> listConnectorStatusEvents(String channelId, Instant from, Instant to) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("channelId", channelId);
            params.put("from", toTimestamp(from));
            params.put("to", toTimestamp(to));

            List<Map<String, Object>> rows = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("listConnectorStatusEvents"), params);

            List<ConnectorStatusEvent> results = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                results.add(toConnectorStatusEvent(row));
            }
            return results;
        } catch (Exception e) {
            log.error("Failed to list connector status events for channel {} in range {}..{}", channelId, from, to, e);
            throw new RepositoryException(e);
        }
    }

    // ========== Map <-> DTO conversion ==========

    private static ConnectorStatusEvent toConnectorStatusEvent(Map<String, Object> row) {
        ConnectorStatusEvent event = new ConnectorStatusEvent();

        Object id = row.get("id");
        event.setId(id != null ? ((Number) id).longValue() : null);

        event.setChannelId((String) row.get("channel_id"));

        Object metadataId = row.get("metadata_id");
        event.setMetadataId(metadataId != null ? ((Number) metadataId).intValue() : 0);

        event.setPreviousState((String) row.get("previous_state"));
        event.setNewState((String) row.get("new_state"));
        event.setChangedTime(toInstant(row.get("changed_time")));
        return event;
    }

    /** Converts an {@link Instant} to the {@link Timestamp} MyBatis/JDBC expects as a bound parameter. */
    private static Timestamp toTimestamp(Instant value) {
        return value != null ? Timestamp.from(value) : null;
    }

    /** Converts the {@link Timestamp} JDBC hands back from a SELECT to an {@link Instant}. */
    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        return ((Timestamp) value).toInstant();
    }
}
