package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.session.SqlSessionManager;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.alert.ActionConditionMatcher;
import org.openintegrationengine.plugins.sentinel.server.alert.AlertPayload;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;

/** Persisted regressions for malformed references and concurrent identity changes. */
class ImportReferenceSafetyPersistenceTest extends MonitorIdentityDatabaseTestSupport {
    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "true", "17", "1.5", "\" \""})
    void malformedSuppressionReferenceMustNotEraseExistingDependency(String malformed) throws Exception {
        for (String literalName : List.of("true", "17", "1.5")) {
            MonitorService.create(monitor(literalName, "{}", null), 99);
        }
        Monitor parent = MonitorService.create(monitor("Parent", "{}", null), 99);
        MonitorService.create(monitor("Child", "{}", parent.getId()), 99);
        ObjectNode incoming = monitorEntry("Child", "{\"noDataForSeconds\":60}", "Parent");
        incoming.set("suppressedByMonitorName", Json.mapper().readTree(malformed));
        ObjectNode doc = document();
        doc.putArray("monitors").add(monitorEntry("Grandchild", "{}", "Child")).add(incoming);
        doc.putArray("actions").add(actionEntry("Recipient", condition("=", "Child")));
        String before = snapshot();
        var result = ExportImportService.importDocument(doc, 99);
        System.out.println("MALFORMED_SUPPRESSION " + malformed + " result=" + Json.write(result)
                + " parentAfter=" + namedMonitor("Child").getSuppressedByMonitorId());
        assertEquals(before, snapshot(), "Malformed reference must reject the entry and all dependents");
        assertEquals(3, result.getSkipped());
    }

    @Test
    void concurrentRenameAndRecreateMustNotRouteToFormerNameOwner() {
        Monitor original = MonitorService.create(monitor("Pager", "{}", null), 99);
        ObjectNode doc = document();
        doc.putArray("actions").add(actionEntry("Recipient", condition("=", "Pager")));
        AtomicBoolean interleaved = new AtomicBoolean();
        try (MockedStatic<ActionService> actions = mockStatic(ActionService.class, CALLS_REAL_METHODS)) {
            actions.when(ActionService::list).thenAnswer(invocation -> {
                if (interleaved.compareAndSet(false, true)) {
                    MonitorService.update(original.getId(), monitor("Retired pager", "{}", null), 100);
                    MonitorService.create(monitor("Pager", "{}", null), 100);
                }
                return invocation.callRealMethod();
            });
            var result = ExportImportService.importDocument(doc, 99);
            System.out.println("CONCURRENT_RENAME result=" + Json.write(result) + " rows=" + snapshot());
        }
        assertTrue(interleaved.get());
        if (namedAction("Recipient") != null) {
            AlertPayload oldPayload = mock(AlertPayload.class);
            when(oldPayload.getMonitorId()).thenReturn(original.getId());
            assertFalse(ActionConditionMatcher.matches(namedAction("Recipient"), oldPayload),
                    "Imported Pager reference must not route to Retired pager after current Pager has another id");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"17", "\"17\"", "{}", "[]", "true"})
    void sourceSuppressionIdsAreDiagnosticsNeverDestinationIds(String value) throws Exception {
        Monitor parent = MonitorService.create(monitor("Parent", "{}", null), 99);
        MonitorService.create(monitor("Child", "{}", parent.getId()), 99);
        ObjectNode incoming = monitorEntry("Child", "{}", "Parent");
        incoming.set("suppressedByMonitorId", Json.mapper().readTree(value));
        ObjectNode doc = document(); doc.putArray("monitors").add(incoming);
        String before = snapshot();
        assertEquals(1, ExportImportService.importDocument(doc, 99).getSkipped());
        assertEquals(before, snapshot());
    }

    @Test
    void exportRetainsUnresolvedSuppressionDiagnostic() {
        Monitor missing = monitor("Missing from export snapshot", "{}", 987654);
        missing.setId(1);
        try (MockedStatic<MonitorService> monitors = mockStatic(MonitorService.class, CALLS_REAL_METHODS)) {
            monitors.when(MonitorService::list).thenReturn(List.of(missing));
            ObjectNode exported = ExportImportService.export();
            assertEquals(987654, exported.path("monitors").get(0).path("suppressedByMonitorId").asInt());
            assertEquals(1, ExportImportService.importDocument(exported, 99).getSkipped());
        }
        assertTrue(MonitorService.list().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"condition", "suppression", "escalation", "monitor-destination", "action-destination",
            "condition-unchanged", "suppression-unchanged", "escalation-unchanged",
            "monitor-destination-unchanged", "action-destination-unchanged"})
    void renameAndRecreationAfterResolutionRejectsTheEntryAndRetryUsesCurrentOwner(String scenario) throws Exception {
        String kind = scenario.replace("-unchanged", "");
        boolean unchanged = scenario.endsWith("-unchanged");
        boolean actionReference = kind.equals("escalation") || kind.equals("action-destination");
        int id = actionReference ? ActionService.create(action("Pager", "[]"), 99).getId()
                : MonitorService.create(monitor("Pager", "{}", null), 99).getId();
        ObjectNode doc = referenceDocument(kind);
        if (unchanged) {
            if (kind.equals("suppression")) MonitorService.create(monitor("Child", "{}", id), 99);
            else if (kind.equals("condition")) ActionService.create(action("Recipient", condition("=", String.valueOf(id))), 99);
            else if (kind.equals("escalation")) {
                Action recipient = action("Recipient", "[]"); recipient.setEscalateToActionId(id);
                ActionService.create(recipient, 99);
            } else if (kind.equals("monitor-destination")) {
                ((ObjectNode) doc.path("monitors").get(0)).put("configJson", "{}");
            } else ((ObjectNode) doc.path("actions").get(0)).putNull("description");
        }
        AtomicBoolean changed = new AtomicBoolean();
        try (MockedStatic<MonitorRepository> monitors = mockStatic(MonitorRepository.class, CALLS_REAL_METHODS);
                MockedStatic<ActionRepository> actions = mockStatic(ActionRepository.class, CALLS_REAL_METHODS)) {
            org.mockito.stubbing.Answer<Object> race = invocation -> {
                if (changed.compareAndSet(false, true)) {
                    try (Connection db = connection()) {
                        renameAndRecreate(db, actionReference ? "sentinel_action" : "sentinel_monitor", id);
                    }
                }
                return invocation.callRealMethod();
            };
            if (actionReference) actions.when(() -> ActionRepository.lockAction(id)).thenAnswer(race);
            else monitors.when(() -> MonitorRepository.lockMonitor(id)).thenAnswer(race);
            var result = ExportImportService.importDocument(doc, 99);
            assertTrue(changed.get(), "The race must execute after resolution, at the lock boundary");
            assertEquals(1, result.getSkipped());
            assertTrue(result.getEntries().get(0).getReason().contains("changed during import"));
            assertEquals(0, result.getCreated() + result.getUpdated());
            if (!unchanged) {
                assertNull(namedAction("Recipient"));
                assertNull(namedMonitor("Child"));
            }
        }
        var retry = ExportImportService.importDocument(doc, 99);
        if (unchanged && kind.endsWith("-destination")) assertEquals(1, retry.getSkipped());
        else assertEquals(1, retry.getCreated() + retry.getUpdated());
        if (kind.equals("condition")) {
            assertEquals(String.valueOf(namedMonitor("Pager").getId()),
                    Json.mapper().readTree(namedAction("Recipient").getConditionJson()).get(0).path("value").asText());
        } else if (kind.equals("suppression")) {
            assertEquals(namedMonitor("Pager").getId(), namedMonitor("Child").getSuppressedByMonitorId());
        } else if (kind.equals("escalation")) {
            assertEquals(namedAction("Pager").getId(), namedAction("Recipient").getEscalateToActionId());
        }
        String persisted = snapshot();
        assertEquals(1, ExportImportService.importDocument(doc, 99).getSkipped());
        assertEquals(persisted, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"condition", "suppression", "escalation", "condition-update", "suppression-update", "escalation-update"})
    void referenceLockHoldsThroughActualWriteAndCommit(String scenario) throws Exception {
        String kind = scenario.replace("-update", "");
        boolean update = scenario.endsWith("-update");
        boolean actionReference = kind.equals("escalation");
        int id = actionReference ? ActionService.create(action("Pager", "[]"), 99).getId()
                : MonitorService.create(monitor("Pager", "{}", null), 99).getId();
        String table = actionReference ? "sentinel_action" : "sentinel_monitor";
        if (update) {
            if (kind.equals("suppression")) MonitorService.create(monitor("Child", "{}", null), 99);
            else ActionService.create(action("Recipient", "[]"), 99);
        }
        ExecutorService worker = Executors.newSingleThreadExecutor();
        List<Future<?>> writes = new ArrayList<>();
        CountDownLatch attempted = new CountDownLatch(1);
        try (MockedStatic<MonitorRepository> monitors = mockStatic(MonitorRepository.class, CALLS_REAL_METHODS);
                MockedStatic<ActionRepository> actions = mockStatic(ActionRepository.class, CALLS_REAL_METHODS)) {
            org.mockito.stubbing.Answer<Object> atWrite = invocation -> {
                writes.add(worker.submit(() -> {
                    try (Connection db = connection(); PreparedStatement sql = db.prepareStatement(
                            "UPDATE " + table + " SET name = 'Retired pager' WHERE id = ?")) {
                        sql.setInt(1, id); attempted.countDown(); sql.executeUpdate();
                    } catch (SQLException error) { throw new RuntimeException(error); }
                }));
                assertTrue(attempted.await(5, TimeUnit.SECONDS));
                boolean waiting = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                try (Connection observer = connection(); Statement sql = observer.createStatement()) {
                    while (!waiting && System.nanoTime() < deadline) {
                        try (ResultSet locks = sql.executeQuery("SELECT STATE FROM SYSCS_DIAG.LOCK_TABLE WHERE TABLENAME = '"
                                + table.toUpperCase(Locale.ROOT) + "'")) {
                            while (locks.next()) waiting |= "WAIT".equals(locks.getString(1));
                        }
                        if (!waiting) Thread.sleep(10);
                    }
                }
                assertTrue(waiting, "Independent writer must wait on the imported reference's database lock");
                assertFalse(writes.get(0).isDone());
                return invocation.callRealMethod();
            };
            if (kind.equals("suppression")) {
                if (update) monitors.when(() -> MonitorRepository.updateMonitor(any())).thenAnswer(atWrite);
                else monitors.when(() -> MonitorRepository.insertMonitor(any())).thenAnswer(atWrite);
            } else {
                if (update) actions.when(() -> ActionRepository.updateAction(any())).thenAnswer(atWrite);
                else actions.when(() -> ActionRepository.insertAction(any())).thenAnswer(atWrite);
            }
            var result = ExportImportService.importDocument(referenceDocument(kind), 99);
            assertEquals(1, update ? result.getUpdated() : result.getCreated());
            writes.get(0).get(5, TimeUnit.SECONDS);
            System.out.println("LOCK_HELD_THROUGH_COMMIT " + kind + " independent writer resumed after import commit");
        } finally {
            worker.shutdown();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS), "Owned writer must exit before database cleanup");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"monitor", "action", "monitor-update", "action-update"})
    void failedCommitKeepsPriorEntriesSuppressesAuditAndAllowsIdempotentRetry(String scenario) {
        String type = scenario.replace("-update", "");
        boolean update = scenario.endsWith("-update");
        ObjectNode doc = document();
        String beforeSecond = null;
        if (update) {
            if (type.equals("monitor")) beforeSecond = Json.write(MonitorService.create(monitor("Second", "{}", null), 99));
            else beforeSecond = Json.write(ActionService.create(action("Second", "[]"), 99));
        }
        if (type.equals("monitor")) {
            doc.putArray("monitors").add(monitorEntry("First", "{}", null)).add(monitorEntry("Second", "{}", "First"));
        } else {
            doc.putArray("actions").add(actionEntry("First", "[]")).add(actionEntry("Second", "[]").put("description", "Imported edit"));
        }
        audit.clearInvocations();
        SqlSessionManager failing = spy(sessions);
        AtomicInteger commits = new AtomicInteger();
        doAnswer(invocation -> {
            if (commits.incrementAndGet() == 2) throw new IllegalStateException("injected commit failure");
            return invocation.callRealMethod();
        }).when(failing).commit();
        when(engine.getSqlSessionManager()).thenReturn(failing);
        try {
            assertThrows(IllegalStateException.class, () -> ExportImportService.importDocument(doc, 99));
            assertFalse(failing.isManagedSessionStarted());
        } finally { when(engine.getSqlSessionManager()).thenReturn(sessions); }
        if (type.equals("monitor")) {
            assertNotNull(namedMonitor("First"));
            if (update) assertEquals(beforeSecond, Json.write(namedMonitor("Second")));
            else assertNull(namedMonitor("Second"));
            audit.verify(() -> SentinelAuditLog.monitorCreated(eq(99), argThat(m -> m.getName().equals("First"))));
            audit.verify(() -> SentinelAuditLog.monitorCreated(eq(99), argThat(m -> m.getName().equals("Second"))), never());
            audit.verify(() -> SentinelAuditLog.monitorUpdated(eq(99), any()), never());
        } else {
            assertNotNull(namedAction("First"));
            if (update) assertEquals(beforeSecond, Json.write(namedAction("Second")));
            else assertNull(namedAction("Second"));
            audit.verify(() -> SentinelAuditLog.actionCreated(eq(99), argThat(a -> a.getName().equals("First"))));
            audit.verify(() -> SentinelAuditLog.actionCreated(eq(99), argThat(a -> a.getName().equals("Second"))), never());
            audit.verify(() -> SentinelAuditLog.actionUpdated(eq(99), any()), never());
        }
        var retry = ExportImportService.importDocument(doc, 99);
        assertEquals(1, update ? retry.getUpdated() : retry.getCreated()); assertEquals(1, retry.getSkipped());
        String persisted = snapshot();
        assertEquals(2, ExportImportService.importDocument(doc, 99).getSkipped());
        assertEquals(persisted, snapshot());
    }

    @Test
    void destinationOnlyEscalationAncestorsRemainIdBasedEvenWithLegacyNameCollisions() {
        Action first = ActionService.create(action("Pager", "[]"), 99);
        Action legacy = action(" pager ", "[]");
        legacy.setCreatedBy(99); legacy.setUpdatedBy(99);
        legacy.setCreatedTime(java.time.Instant.now()); legacy.setUpdatedTime(legacy.getCreatedTime());
        ActionRepository.insertAction(legacy);
        Action bridge = action("Bridge", "[]"); bridge.setEscalateToActionId(first.getId());
        ActionService.create(bridge, 99);
        ObjectNode doc = document();
        doc.putArray("actions").add(actionEntry("Recipient", "[]").put("escalateToActionName", "Bridge"));
        assertEquals(1, ExportImportService.importDocument(doc, 99).getCreated());
        assertEquals(1, ExportImportService.importDocument(doc, 99).getSkipped());
        assertEquals(first.getId(), namedAction("Bridge").getEscalateToActionId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"condition", "suppression", "escalation"})
    void changedDestinationOnlyAncestorRejectsEntry(String kind) throws Exception {
        boolean actionReference = kind.equals("escalation");
        int ancestor;
        if (actionReference) {
            ancestor = ActionService.create(action("Ancestor", "[]"), 99).getId();
            Action pager = action("Pager", "[]"); pager.setEscalateToActionId(ancestor);
            ActionService.create(pager, 99);
        } else {
            ancestor = MonitorService.create(monitor("Ancestor", "{}", null), 99).getId();
            MonitorService.create(monitor("Pager", "{}", ancestor), 99);
        }
        try (MockedStatic<MonitorRepository> monitors = mockStatic(MonitorRepository.class, CALLS_REAL_METHODS);
                MockedStatic<ActionRepository> actions = mockStatic(ActionRepository.class, CALLS_REAL_METHODS)) {
            org.mockito.stubbing.Answer<Object> race = invocation -> {
                try (Connection db = connection(); PreparedStatement sql = db.prepareStatement("UPDATE "
                        + (actionReference ? "sentinel_action" : "sentinel_monitor") + " SET description = 'Concurrent edit' WHERE id = ?")) {
                    sql.setInt(1, ancestor); sql.executeUpdate();
                }
                return invocation.callRealMethod();
            };
            if (actionReference) actions.when(() -> ActionRepository.lockAction(ancestor)).thenAnswer(race);
            else monitors.when(() -> MonitorRepository.lockMonitor(ancestor)).thenAnswer(race);
            var result = ExportImportService.importDocument(referenceDocument(kind), 99);
            assertEquals(1, result.getSkipped());
            assertTrue(result.getEntries().get(0).getReason().contains("changed during import"));
        }
        assertEquals(1, ExportImportService.importDocument(referenceDocument(kind), 99).getCreated());
    }

    @Test
    void locksAllMonitorIdsThenAllActionIdsInSortedOrder() {
        Monitor first = MonitorService.create(monitor("First monitor", "{}", null), 99);
        Monitor second = MonitorService.create(monitor("Second monitor", "{}", first.getId()), 99);
        Action recipient = ActionService.create(action("Recipient", "[]"), 99);
        Action ancestor = ActionService.create(action("Ancestor", "[]"), 99);
        Action target = action("Target", "[]"); target.setEscalateToActionId(ancestor.getId());
        target = ActionService.create(target, 99);
        ObjectNode doc = document();
        doc.putArray("actions").add(actionEntry("Recipient", condition("IN", "Second monitor", "First monitor"))
                .put("escalateToActionName", "Target"));
        List<String> locks = new ArrayList<>();
        try (MockedStatic<MonitorRepository> monitors = mockStatic(MonitorRepository.class, CALLS_REAL_METHODS);
                MockedStatic<ActionRepository> actions = mockStatic(ActionRepository.class, CALLS_REAL_METHODS)) {
            monitors.when(() -> MonitorRepository.lockMonitor(anyInt())).thenAnswer(invocation -> {
                locks.add("monitor:" + invocation.getArgument(0)); return invocation.callRealMethod();
            });
            actions.when(() -> ActionRepository.lockAction(anyInt())).thenAnswer(invocation -> {
                locks.add("action:" + invocation.getArgument(0)); return invocation.callRealMethod();
            });
            assertEquals(1, ExportImportService.importDocument(doc, 99).getUpdated());
        }
        assertEquals(List.of("monitor:" + first.getId(), "monitor:" + second.getId(),
                "action:" + recipient.getId(), "action:" + ancestor.getId(), "action:" + target.getId()), locks);
    }

    private static ObjectNode referenceDocument(String kind) {
        ObjectNode doc = document();
        if (kind.equals("suppression")) doc.putArray("monitors").add(monitorEntry("Child", "{}", "Pager"));
        else if (kind.equals("monitor-destination")) {
            doc.putArray("monitors").add(monitorEntry("Pager", "{\"noDataForSeconds\":60}", null));
        } else {
            ObjectNode action = actionEntry(kind.equals("action-destination") ? "Pager" : "Recipient",
                    kind.equals("condition") ? condition("=", "Pager") : "[]");
            if (kind.equals("escalation")) action.put("escalateToActionName", "Pager");
            if (kind.equals("action-destination")) action.put("description", "Imported edit");
            doc.putArray("actions").add(action);
        }
        return doc;
    }

    private static void renameAndRecreate(Connection db, String table, int id) throws SQLException {
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        try (PreparedStatement select = db.prepareStatement("SELECT * FROM " + table + " WHERE id = ?")) {
            select.setInt(1, id);
            try (ResultSet row = select.executeQuery()) {
                assertTrue(row.next());
                for (int column = 1; column <= row.getMetaData().getColumnCount(); column++) {
                    if (row.getMetaData().getColumnName(column).equalsIgnoreCase("id")) continue;
                    columns.add(row.getMetaData().getColumnName(column));
                    values.add(row.getMetaData().getColumnType(column) == Types.CLOB
                            ? row.getString(column) : row.getObject(column));
                }
            }
        }
        try (PreparedStatement update = db.prepareStatement("UPDATE " + table + " SET name = 'Retired pager' WHERE id = ?")) {
            update.setInt(1, id); assertEquals(1, update.executeUpdate());
        }
        try (PreparedStatement insert = db.prepareStatement("INSERT INTO " + table + " (" + String.join(",", columns)
                + ") VALUES (" + String.join(",", Collections.nCopies(columns.size(), "?")) + ")")) {
            for (int index = 0; index < values.size(); index++) insert.setObject(index + 1, values.get(index));
            assertEquals(1, insert.executeUpdate());
        }
    }

    @Test
    void allThreeMonitorAcyclicRewiresAreOrderIndependentAndIdempotent() throws Exception {
        List<int[]> graphs = new ArrayList<>();
        for (int a=-1; a<3; a++) for (int b=-1; b<3; b++) for (int c=-1; c<3; c++) {
            int[] graph = {a,b,c};
            if (acyclic(graph)) graphs.add(graph);
        }
        int[][] orders={{0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0}};
        List<String> failures=new ArrayList<>();
        int checked=0;
        for (int[] before: graphs) for (int[] after: graphs) for (int[] order: orders) {
            clearRows();
            Monitor[] rows=new Monitor[3];
            for(int i=0;i<3;i++) rows[i]=MonitorService.create(monitor("M"+i,"{}",null),99);
            for(int i=0;i<3;i++) if(before[i]>=0) {
                Monitor row=rows[i]; row.setSuppressedByMonitorId(rows[before[i]].getId());
                MonitorRepository.updateMonitor(row);
            }
            ObjectNode doc=document();
            var entries=doc.putArray("monitors");
            for(int i: order) entries.add(monitorEntry("M"+i,"{}",after[i]<0?null:"M"+after[i]));
            var result=ExportImportService.importDocument(doc,99);
            boolean correct=true;
            for(int i=0;i<3;i++) {
                Integer expected=after[i]<0?null:rows[after[i]].getId();
                correct &= Objects.equals(expected,namedMonitor("M"+i).getSuppressedByMonitorId());
            }
            if(!correct) failures.add("before="+Arrays.toString(before)+" after="+Arrays.toString(after)
                    +" order="+Arrays.toString(order)+" result="+Json.write(result));
            else {
                String stable=snapshot();
                var retry=ExportImportService.importDocument(doc,99);
                if(!stable.equals(snapshot()) || retry.getUpdated()!=0 || retry.getCreated()!=0)
                    failures.add("retry changed rows: "+Json.write(retry));
            }
            checked++;
        }
        System.out.println("ACYCLIC_REWIRES checked="+checked+" failures="+failures.size());
        failures.stream().limit(10).forEach(System.out::println);
        assertTrue(failures.isEmpty(),"Failures="+failures.size()+" first="+(failures.isEmpty()?"":failures.get(0)));
    }


    private static boolean acyclic(int[] graph) {
        for(int start=0;start<3;start++) {
            Set<Integer> seen=new HashSet<>(); int cursor=start;
            while(cursor>=0) {if(!seen.add(cursor)) return false;cursor=graph[cursor];}
        }
        return true;
    }
}
