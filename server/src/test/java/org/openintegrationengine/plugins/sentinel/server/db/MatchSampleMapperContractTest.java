/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The schema v14 and v15 statements (match samples, subjects and alert kinds) in every
 * shipped mapper, without a database. Derby also runs them in
 * DatabaseVendorMatrixTest; the other four run only in the opt-in db-matrix,
 * so this is what catches a typo in those four in an ordinary build.
 */
class MatchSampleMapperContractTest {

    /** Statements whose rows become an AlertEvent or a TriggerState. */
    private static final Set<String> SUBJECT_ROW_STATEMENTS = Set.of(
            "getAlertEvent", "listAlertEvents", "listRecentAlertEvents", "listPendingProblemAlertEvents",
            "listPendingResolvedAlertEvents", "getTriggerState", "listTriggerStatesByMonitor");

    @ParameterizedTest
    @ValueSource(strings = {"derby", "mysql", "oracle", "postgres", "sqlserver"})
    void theInsertBindsEveryColumnWithATypedNullableChannel(String vendor) throws Exception {
        Map<String, Object> row = new HashMap<>();
        row.put("channel_id", null);
        List<ParameterMapping> bindings = mapper(vendor).getMappedStatement("Sentinel.insertMatchSample")
                .getBoundSql(row).getParameterMappings();
        assertEquals(List.of("node_id", "monitor_id", "channel_id", "sample_time", "match_count", "samples_json",
                "complete", "second_counts"), properties(bindings), vendor);
        // Independent of the engine's jdbcTypeForNull setting.
        assertEquals(JdbcType.VARCHAR, bindings.get(2).getJdbcType(), vendor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"derby", "mysql", "oracle", "postgres", "sqlserver"})
    void countRowsAreReadOneByOneWithoutAChannel(String vendor) throws Exception {
        Configuration mapper = mapper(vendor);
        String where = "FROM sentinel_match_sample WHERE monitor_id = ? AND channel_id ";
        String range = " AND sample_time >= ? AND sample_time <= ? ";
        MappedStatement counts = mapper.getMappedStatement("Sentinel.listMatchSampleCounts");
        BoundSql node = counts.getBoundSql(Map.of("monitorId", 7));
        assertEquals("SELECT node_id, sample_time, match_count, second_counts, complete "
                + where + "IS NULL" + range + "ORDER BY node_id, sample_time, id", normalized(node), vendor);
        assertEquals(List.of("monitorId", "from", "to"), properties(node.getParameterMappings()), vendor);
        List<ResultMapping> nodeColumns = counts.getResultMaps().get(0).getResultMappings();
        assertEquals(List.of("node_id", "sample_time", "match_count", "second_counts", "complete"),
                columns(nodeColumns), vendor);
        assertEquals(Timestamp.class, nodeColumns.get(1).getJavaType(), vendor + " sample_time must map to Timestamp");
        // Derby and Oracle hand a CLOB back as a Clob, not a String.
        assertEquals(vendor.equals("derby") || vendor.equals("oracle"),
                nodeColumns.get(3).getTypeHandler() instanceof ClobStringTypeHandler, vendor + " second_counts type handler");
    }

    @ParameterizedTest
    @ValueSource(strings = {"derby", "mysql", "oracle", "postgres", "sqlserver"})
    void recentSamplesAreOneNodesNewestRowsThatKeepSamples(String vendor) throws Exception {
        MappedStatement recent = mapper(vendor).getMappedStatement("Sentinel.listRecentMatchSamples");
        BoundSql bound = recent.getBoundSql(new HashMap<>(Map.of("limit", 10)));
        String sql = normalized(bound);
        assertTrue(sql.startsWith("SELECT id, node_id, monitor_id, channel_id, sample_time, match_count, samples_json, "
                + "complete FROM sentinel_match_sample WHERE monitor_id = ? AND node_id = ? "
                + "AND sample_time >= ? AND sample_time <= ? AND samples_json IS NOT NULL "
                + "ORDER BY sample_time DESC, id DESC "), vendor + " " + sql);
        String limit = vendor.equals("mysql") || vendor.equals("postgres")
                ? "LIMIT ?" : "OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY";
        assertTrue(sql.endsWith(limit), vendor + " " + sql);
        assertEquals(List.of("monitorId", "nodeId", "from", "to", "limit"), properties(bound.getParameterMappings()), vendor);
        List<ResultMapping> columns = recent.getResultMaps().get(0).getResultMappings();
        assertEquals(List.of("id", "node_id", "monitor_id", "channel_id", "sample_time", "match_count", "samples_json",
                "complete"), columns(columns), vendor);
        assertEquals(Timestamp.class, columns.get(4).getJavaType(), vendor + " sample_time must map to Timestamp");
        // Derby and Oracle hand a CLOB back as a Clob, not a String.
        boolean clob = columns.get(6).getTypeHandler() instanceof ClobStringTypeHandler;
        assertEquals(vendor.equals("derby") || vendor.equals("oracle"), clob, vendor + " samples_json type handler");
    }

    @ParameterizedTest
    @ValueSource(strings = {"derby", "mysql", "oracle", "postgres", "sqlserver"})
    void thePruneTakesALiteralChunkAndCoversEveryMonitor(String vendor) throws Exception {
        Map<String, Object> prune = new HashMap<>();
        prune.put("cutoff", null);
        prune.put("chunkSize", 5000);
        BoundSql bound = mapper(vendor).getMappedStatement("Sentinel.deleteMatchSamplesOlderThan").getBoundSql(prune);
        assertTrue(bound.getSql().contains("5000"), vendor + " chunk size must be a literal");
        assertEquals(List.of("cutoff"), properties(bound.getParameterMappings()), vendor);
        assertFalse(bound.getSql().contains("monitor_id"), vendor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"derby", "mysql", "oracle", "postgres", "sqlserver"})
    void subjectInsertsBindTheSubjectAndATypedNullableChannel(String vendor) throws Exception {
        Configuration mapper = mapper(vendor);
        Map<String, List<String>> columns = Map.of(
                "insertAlertEvent", List.of("monitor_id", "subject_type", "event_kind", "channel_id"),
                "insertTriggerState", List.of("monitor_id", "subject_type", "channel_id"));
        for (Map.Entry<String, List<String>> statement : columns.entrySet()) {
            Map<String, Object> row = new HashMap<>();
            row.put("channel_id", null);
            BoundSql bound = mapper.getMappedStatement("Sentinel." + statement.getKey()).getBoundSql(row);
            List<ParameterMapping> bindings = bound.getParameterMappings();
            List<String> leading = statement.getValue();
            assertEquals(leading, properties(bindings.subList(0, leading.size())), vendor + " " + statement.getKey());
            assertEquals(JdbcType.VARCHAR, bindings.get(leading.size() - 1).getJdbcType(), vendor + " " + statement.getKey());
            assertTrue(normalized(bound).contains("(" + String.join(", ", leading) + ", metadata_id,"), vendor);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"derby", "mysql", "oracle", "postgres", "sqlserver"})
    void triggerLookupMatchesNullsAndComparesTheSubjectDirectly(String vendor) throws Exception {
        Configuration mapper = mapper(vendor);
        Map<String, Object> server = new HashMap<>();
        server.put("monitorId", 7);
        server.put("subjectType", "SERVER");
        server.put("channelId", null);
        server.put("metadataId", null);
        assertTrue(normalized(mapper, "getTriggerState", server).endsWith(
                "WHERE monitor_id = ? AND channel_id IS NULL AND metadata_id IS NULL AND subject_type = ?"), vendor);

        Map<String, Object> channel = new HashMap<>(server);
        channel.put("subjectType", "CHANNEL");
        channel.put("channelId", "c");
        channel.put("metadataId", 1);
        assertTrue(normalized(mapper, "getTriggerState", channel).endsWith(
                "WHERE monitor_id = ? AND channel_id = ? AND metadata_id = ? AND subject_type = ?"), vendor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"derby", "mysql", "oracle", "postgres", "sqlserver"})
    void everyAlertAndTriggerRowCarriesItsSubjectAndEveryAlertRowItsKind(String vendor) throws Exception {
        Configuration mapper = mapper(vendor);
        Map<String, Object> params = listParameters();
        // Discovered rather than listed, so a future read that selects channel_id without subject_type fails here.
        Set<String> discovered = new TreeSet<>();
        for (String id : new ArrayList<>(mapper.getMappedStatementNames())) {
            if (!id.startsWith("Sentinel.")) continue;
            MappedStatement statement = mapper.getMappedStatement(id);
            if (statement.getSqlCommandType() != SqlCommandType.SELECT) continue;
            String[] parts = statement.getBoundSql(params).getSql().replaceAll("\\s+", " ").trim().split(" FROM ", 2);
            if (parts.length < 2 || !parts[0].contains("channel_id")) continue;
            boolean alert = parts[1].startsWith("sentinel_alert_event");
            if (!alert && !parts[1].startsWith("sentinel_trigger_state")) continue;
            String name = id.substring("Sentinel.".length());
            discovered.add(name);
            for (String column : alert ? List.of("subject_type", "event_kind") : List.of("subject_type")) {
                assertTrue(parts[0].contains(column), vendor + " " + name + " omits " + column);
                if (!statement.getResultMaps().isEmpty()
                        && !statement.getResultMaps().get(0).getResultMappings().isEmpty()) {
                    assertTrue(columns(statement.getResultMaps().get(0).getResultMappings()).contains(column),
                            vendor + " " + name + " result map drops " + column);
                }
            }
        }
        assertEquals(new TreeSet<>(SUBJECT_ROW_STATEMENTS), discovered, vendor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"derby", "mysql", "oracle", "postgres", "sqlserver"})
    void theAlertListsAndCountFilterByKindOnlyWhenAsked(String vendor) throws Exception {
        Configuration mapper = mapper(vendor);
        for (String statement : List.of("listAlertEvents", "countAlertEvents", "listRecentAlertEvents")) {
            Map<String, Object> params = listParameters();
            assertFalse(afterFrom(normalized(mapper, statement, params)).contains("event_kind"), vendor + " " + statement);
            params.put("kind", "NOTIFICATION");
            BoundSql bound = mapper.getMappedStatement("Sentinel." + statement).getBoundSql(params);
            String where = afterFrom(normalized(bound));
            assertTrue(where.startsWith("WHERE event_kind = ?"), vendor + " " + statement + " " + where);
            assertEquals(List.of("kind"), properties(bound.getParameterMappings()).stream().filter("kind"::equals).toList(),
                    vendor + " " + statement);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"derby", "mysql", "oracle", "postgres", "sqlserver"})
    void monitorHistoryAlwaysFiltersByKind(String vendor) throws Exception {
        BoundSql bound = mapper(vendor).getMappedStatement("Sentinel.listMonitorHistory").getBoundSql(new HashMap<>());
        assertTrue(normalized(bound).contains(
                "WHERE monitor_id = ? AND event_kind = ? AND opened_time >= ? AND opened_time <= ?"), vendor);
        assertEquals(List.of("monitorId", "kind", "from", "to"), properties(bound.getParameterMappings()), vendor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"derby", "mysql", "oracle", "postgres", "sqlserver"})
    void detailsAreUpdatedOnlyOnAnOpenProblem(String vendor) throws Exception {
        BoundSql bound = mapper(vendor).getMappedStatement("Sentinel.updateAlertEventDetails").getBoundSql(new HashMap<>());
        assertEquals("UPDATE sentinel_alert_event SET details_json = ? WHERE id = ? AND status = 'PROBLEM'",
                normalized(bound), vendor);
        assertEquals(List.of("details_json", "id"), properties(bound.getParameterMappings()), vendor);
    }

    private static String afterFrom(String sql) {
        return sql.split(" FROM sentinel_alert_event", 2)[1].trim();
    }

    private static Map<String, Object> listParameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("sortColumn", "opened_time");
        params.put("sortDir", "DESC");
        params.put("offset", 0);
        params.put("limit", 25);
        return params;
    }

    private static List<String> properties(List<ParameterMapping> bindings) {
        return bindings.stream().map(ParameterMapping::getProperty).toList();
    }

    private static List<String> columns(List<ResultMapping> mappings) {
        return mappings.stream().map(ResultMapping::getProperty).toList();
    }

    private static String normalized(BoundSql bound) {
        return bound.getSql().replaceAll("\\s+", " ").trim();
    }

    private static String normalized(Configuration mapper, String statement, Map<String, Object> params) {
        return normalized(mapper.getMappedStatement("Sentinel." + statement).getBoundSql(params));
    }

    private static Configuration mapper(String vendor) throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream in = MatchSampleMapperContractTest.class.getResourceAsStream("/mapper/" + vendor + "-sqlmap.xml")) {
            new XMLMapperBuilder(in, configuration, vendor, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }
}
