/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.servlet;

import com.fasterxml.jackson.databind.JsonNode;
import com.mirth.connect.client.core.ExtensionOperation;
import com.mirth.connect.client.core.Operation;
import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.client.core.api.util.OperationUtil;
import com.mirth.connect.model.User;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.UserController;
import com.mirth.connect.server.util.SqlConfig;
import java.io.InputStream;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.server.service.SentinelAuditLog;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.SentinelServletInterface;
import org.openintegrationengine.plugins.sentinel.shared.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises scoped read endpoints through the real services, repositories and Derby mapper. */
class ScopedMonitoringReadPersistenceTest {
    protected static final String ALLOWED = "00000000-0000-0000-0000-000000000001";
    protected static final String RESTRICTED = "00000000-0000-0000-0000-000000000002";
    protected String url;
    protected SqlSessionManager sessions;
    protected MockedStatic<SqlConfig> sqlConfig;
    protected MockedStatic<ScopeResolver> scopes;
    protected MockedStatic<SentinelAuditLog> audit;
    protected ReaderServlet reader;
    protected Monitor monitor;
    protected AlertEvent event;
    protected List<ChannelInfo> inventory;

    @BeforeEach
    void setup() throws Exception {
        url = "jdbc:derby:memory:scopedMonitoringRead" + UUID.randomUUID().toString().replace("-", "");
        System.out.println("SCOPED_READ_RESOURCE_CREATE pid=" + ProcessHandle.current().pid() + " database=" + url);
        try (Connection connection = DriverManager.getConnection(url + ";create=true");
                MockedStatic<ConfigurationController> controllers = mockStatic(ConfigurationController.class)) {
            controllers.when(ConfigurationController::getInstance).thenReturn(mock(ConfigurationController.class));
            SentinelMigrator migrator = new SentinelMigrator();
            migrator.setConnection(connection);
            migrator.setDatabaseType("derby");
            migrator.migrate();
        }
        Configuration cfg = new Configuration(new Environment("scoped-read", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.apache.derby.jdbc.EmbeddedDriver", url, null, null)));
        cfg.setJdbcTypeForNull(JdbcType.VARCHAR);
        try (InputStream input = getClass().getResourceAsStream("/mapper/derby-sqlmap.xml")) {
            new XMLMapperBuilder(input, cfg, "derby", cfg.getSqlFragments()).parse();
        }
        sessions = SqlSessionManager.newInstance(new SqlSessionFactoryBuilder().build(cfg));
        SqlConfig engine = mock(SqlConfig.class);
        when(engine.getSqlSessionManager()).thenReturn(sessions);
        sqlConfig = mockStatic(SqlConfig.class);
        sqlConfig.when(SqlConfig::getInstance).thenReturn(engine);
        scopes = mockStatic(ScopeResolver.class);
        inventory = new ArrayList<>(List.of(channel(ALLOWED), channel(RESTRICTED)));
        scopes.when(ScopeResolver::listChannels).thenAnswer(call -> new ArrayList<>(inventory));
        scopes.when(() -> ScopeResolver.channelName(ALLOWED)).thenReturn("Allowed channel");
        audit = mockStatic(SentinelAuditLog.class);
        reader = mock(ReaderServlet.class, CALLS_REAL_METHODS);
        reader.allowed = Set.of(ALLOWED);
        monitor = new Monitor();
        monitor.setName("Original monitor");
        monitor.setScopeType(ScopeType.CHANNEL);
        monitor.setScopeId(ALLOWED);
        monitor.setMonitorType(MonitorType.QUEUE_DEPTH);
        monitor.setSeverity(Severity.WARNING);
        monitor.setEnabled(true);
        monitor.setMinConsecutiveBreaches(1);
        monitor.setConfigJson("{}");
        monitor.setCreatedTime(Instant.parse("2026-10-01T00:00:00Z"));
        monitor.setUpdatedTime(monitor.getCreatedTime());
        MonitorRepository.insertMonitor(monitor);
        event = new AlertEvent();
        event.setMonitorId(monitor.getId());
        event.setChannelId(ALLOWED);
        event.setSeverity(Severity.WARNING);
        event.setStatus(AlertStatus.PROBLEM);
        event.setMessage("Historical allowed-channel observation");
        event.setOpenedTime(monitor.getCreatedTime());
        AlertEventRepository.insertAlertEvent(event);
    }

    @AfterEach
    void captureAndCleanup() throws Exception {
        try {
            if (sqlConfig != null && monitor != null && monitor.getId() != null) {
                System.out.println("SCOPED_READ_FINAL_MONITOR " + Json.write(MonitorRepository.getMonitor(monitor.getId())));
                if (event != null && event.getId() != null) {
                    System.out.println("SCOPED_READ_FINAL_EVENT " + Json.write(AlertEventRepository.getAlertEvent(event.getId())));
                }
            }
        } finally {
            if (audit != null) audit.close();
            if (scopes != null) scopes.close();
            if (sqlConfig != null) sqlConfig.close();
            if (sessions != null && sessions.isManagedSessionStarted()) sessions.close();
            if (url != null) {
                SQLException dropped = assertThrows(SQLException.class,
                        () -> DriverManager.getConnection(url + ";drop=true"));
                assertEquals("08006", dropped.getSQLState());
                System.out.println("SCOPED_READ_RESOURCE_DROPPED database=" + url + " SQLState=" + dropped.getSQLState());
            }
        }
    }

    @Test
    void currentTypeOfHiddenMonitorCannotBeInferredByFilteringHistoricalEvents() throws Exception {
        monitor.setScopeId(RESTRICTED);
        monitor.setMonitorType(MonitorType.ERROR_RATE);
        monitor.setName("Hidden current monitor");
        MonitorRepository.updateMonitor(monitor);
        assertStatus(404, () -> reader.getMonitor(monitor.getId()));
        JsonNode detail = tree(reader.getProblem(event.getId()));
        assertTrue(detail.path("monitorType").isNull());
        assertEquals("(unavailable monitor)", detail.path("monitorName").asText());
        List<String> matches = new ArrayList<>();
        for (MonitorType type : MonitorType.values()) {
            JsonNode page = problems(ALLOWED, type.name());
            if (page.path("total").asLong() > 0) matches.add(type.name());
        }
        System.out.println("SCOPED_READ_HIDDEN_CURRENT_TYPE_MATCHES " + matches);
        assertFalse(matches.contains("ERROR_RATE"),
                "The current hidden type is still disclosed by the Problems list/count predicate");
    }

    @Test
    void deletedButStillAuthorizedChannelProblemRemainsReadable() throws Exception {
        inventory.removeIf(channel -> channel.getChannelId().equals(ALLOWED));
        JsonNode page = problems(ALLOWED, null);
        assertEquals(1, page.path("total").asInt());
        assertEquals(event.getId().longValue(), page.path("items").get(0).path("id").asLong());
        System.out.println("SCOPED_READ_DELETED_CHANNEL_LIST " + page);
        JsonNode detail = tree(reader.getProblem(event.getId()));
        assertEquals(event.getId().longValue(), detail.path("event").path("id").asLong());
        assertEquals("(unavailable monitor)", detail.path("monitorName").asText());
        assertTrue(detail.path("monitorType").isNull());
        assertTrue(detail.path("runbookUrl").isNull());
        reader.allowed = Set.of();
        assertEquals(0, problems(ALLOWED, null).path("total").asInt());
        assertStatus(404, () -> reader.getProblem(event.getId()));
        assertStatus(404, () -> reader.acknowledgeProblem(event.getId(), "{}"));
    }

    @Test
    void deletedButStillAuthorizedChannelProblemCanStillBeAcknowledged() throws Exception {
        inventory.removeIf(channel -> channel.getChannelId().equals(ALLOWED));
        JsonNode acknowledged = tree(reader.acknowledgeProblem(event.getId(), "{}"));
        assertEquals(42, acknowledged.path("acknowledgedBy").asInt());
    }

    @Test
    void hiddenMixedScopeCoverageRemainsAvailableWithoutDefinitionsOrOpenProblems() throws Exception {
        String groupId = "00000000-0000-0000-0000-000000000003";
        monitor.setScopeType(ScopeType.GROUP);
        monitor.setScopeId(groupId);
        MonitorRepository.updateMonitor(monitor);
        scopes.when(() -> ScopeResolver.strictGroupChannelIds(groupId)).thenReturn(Set.of(ALLOWED, RESTRICTED));
        try (Connection connection = DriverManager.getConnection(url); Statement sql = connection.createStatement()) {
            assertEquals(1, sql.executeUpdate("UPDATE sentinel_alert_event SET status='RESOLVED'"));
        }
        JsonNode monitors = tree(reader.getMonitors());
        JsonNode problems = tree(reader.getProblems("PROBLEM", null, null, null, null,
                null, null, null, null, null, null, 0, 1000));
        assertTrue(monitors.isEmpty());
        assertEquals(0, problems.path("total").asInt());
        JsonNode coverage = tree(reader.getDashboardCoverage());
        assertFalse(coverage.path("coversAll").asBoolean());
        assertEquals(List.of(ALLOWED), Json.mapper().convertValue(coverage.path("channelIds"), List.class));
    }

    @Test
    void presentAuthorizedChannelStillHasListDetailParity() throws Exception {
        assertEquals(1, problems(ALLOWED, null).path("total").asInt());
        assertEquals("Original monitor", tree(reader.getProblem(event.getId())).path("monitorName").asText());
        assertEquals("QUEUE_DEPTH", tree(reader.getMonitor(monitor.getId())).path("monitorType").asText());
    }

    @Test
    void revokedEventPermissionHidesListAndDetail() throws Exception {
        reader.allowed = Set.of(RESTRICTED);
        assertEquals(0, problems(ALLOWED, null).path("total").asInt());
        assertStatus(404, () -> reader.getProblem(event.getId()));
    }

    @Test
    void nullRedactionCannotFallBackToUnrestrictedMonitorReads() {
        reader.returnNull = true;
        assertStatus(500, reader::getMonitors);
        assertStatus(500, () -> reader.getMonitor(monitor.getId()));
        assertStatus(500, () -> reader.getProblem(event.getId()));
    }

    @Test
    void mixedScopeCannotExposeHiddenCurrentMetadataThroughProblemDetail() throws Exception {
        String group = "00000000-0000-0000-0000-000000000003";
        monitor.setScopeType(ScopeType.GROUP);
        monitor.setScopeId(group);
        monitor.setName("Hidden current name");
        monitor.setMonitorType(MonitorType.ERROR_RATE);
        monitor.setRunbookUrl("https://restricted.example.invalid/internal-runbook");
        MonitorRepository.updateMonitor(monitor);
        scopes.when(() -> ScopeResolver.strictGroupChannelIds(group)).thenReturn(Set.of(ALLOWED, RESTRICTED));
        assertStatus(404, () -> reader.getMonitor(monitor.getId()));
        JsonNode detail = tree(reader.getProblem(event.getId()));
        assertAll(
                () -> assertEquals("(unavailable monitor)", detail.path("monitorName").asText()),
                () -> assertTrue(detail.path("monitorType").isNull()),
                () -> assertTrue(detail.path("runbookUrl").isNull()));
    }

    @Test
    void hiddenSuppressionParentHidesTheChildsCurrentRunbook() throws Exception {
        Monitor parent = duplicateMonitor("Restricted parent", RESTRICTED);
        monitor.setSuppressedByMonitorId(parent.getId());
        monitor.setRunbookUrl("https://restricted.example.invalid/dependency-runbook");
        MonitorRepository.updateMonitor(monitor);
        assertStatus(404, () -> reader.getMonitor(monitor.getId()));
        JsonNode detail = tree(reader.getProblem(event.getId()));
        assertTrue(detail.path("runbookUrl").isNull(), "A hidden dependency cannot bypass the whole-definition visibility rule");
    }

    @Test
    void typeFilterRestrictsRealListAndCountEvenWhenAnotherVisibleMonitorHasTheSameType() throws Exception {
        Monitor hidden = duplicateMonitor("Hidden same-type monitor", RESTRICTED);
        insertEvent(hidden.getId(), ALLOWED, null);
        JsonNode page = tree(reader.getProblems(null, null, ALLOWED, null, "QUEUE_DEPTH",
                null, null, null, null, null, null, 0, 25));
        assertEquals(1, page.path("total").asInt());
        assertEquals(1, page.path("items").size());
        assertEquals(monitor.getId().intValue(), page.path("items").get(0).path("monitorId").asInt());
        JsonNode forbidden = tree(reader.getProblems(null, null, ALLOWED, hidden.getId(), "QUEUE_DEPTH",
                null, null, null, null, null, null, 0, 25));
        assertEquals(0, forbidden.path("total").asInt());
        JsonNode untyped = tree(reader.getProblems(null, null, ALLOWED, null, null,
                null, null, null, null, null, null, 0, 25));
        assertEquals(2, untyped.path("total").asInt(), "Historical authorized events remain readable without current-type filtering");
    }

    @Test
    void explicitChannelFilterDoesNotNarrowMonitorAuthorizationToOnlyThatChannel() throws Exception {
        String alsoAllowed = "00000000-0000-0000-0000-000000000003";
        String group = "00000000-0000-0000-0000-000000000004";
        reader.allowed = Set.of(ALLOWED, alsoAllowed);
        inventory.add(channel(alsoAllowed));
        monitor.setScopeType(ScopeType.GROUP);
        monitor.setScopeId(group);
        MonitorRepository.updateMonitor(monitor);
        scopes.when(() -> ScopeResolver.strictGroupChannelIds(group)).thenReturn(Set.of(ALLOWED, alsoAllowed));
        assertEquals(1, problems(ALLOWED, " queue_depth ").path("total").asInt());
    }

    @Test
    void explicitEmptyMonitorIdListNeverFallsBackToAnUnfilteredQuery() {
        AlertEventFilter filter = new AlertEventFilter();
        filter.setPageSize(25);
        filter.setMonitorIdIn(List.of());
        PagedResult<AlertEvent> page = AlertEventRepository.listAlertEvents(filter);
        assertEquals(0, page.total());
        assertTrue(page.items().isEmpty());
        filter.setMonitorIdIn(List.of(monitor.getId()));
        assertEquals(1, AlertEventRepository.listAlertEvents(filter).total());
    }

    @Test
    void limitedUserDirectoryReturnsOnlySelfAndAcknowledgersOfAuthorizedEvents() throws Exception {
        insertEvent(monitor.getId(), ALLOWED, 9);
        insertEvent(monitor.getId(), RESTRICTED, 77);
        assertEquals(Set.of(9), new HashSet<>(AlertEventRepository.listAcknowledgingUserIds(List.of(ALLOWED))));
        assertTrue(AlertEventRepository.listAcknowledgingUserIds(List.of()).isEmpty());
        try (DirectoryFixture directory = new DirectoryFixture()) {
            assertEquals(Set.of(42, 9), userIds(tree(reader.getCoreUsers())));
            assertSame(directory.original, reader.currentOperation());
            reader.allowed = Set.of();
            assertEquals(Set.of(42), userIds(tree(reader.getCoreUsers())));
            reader.directoryAllowed = true;
            assertEquals(Set.of(42, 9, 77, 99), userIds(tree(reader.getCoreUsers())));
            reader.directoryAllowed = false;
            assertEquals(Set.of(42), userIds(tree(reader.getCoreUsers())));
            assertSame(directory.original, reader.currentOperation());
        }
    }

    @Test
    void userAuthorizationFailureRestoresTheOperationAndDoesNotFetchTheDirectory() throws Exception {
        try (DirectoryFixture directory = new DirectoryFixture()) {
            reader.directoryFailure = new IllegalStateException("synthetic backend failure");
            assertStatus(500, reader::getCoreUsers);
            verifyNoInteractions(directory.users);
            assertSame(directory.original, reader.currentOperation());
        }
    }

    @Test
    void coverageIncludesOnlyAuthorizedMembersOfHiddenEnabledMonitors() throws Exception {
        String group = "00000000-0000-0000-0000-000000000003";
        monitor.setScopeType(ScopeType.GROUP);
        monitor.setScopeId(group);
        MonitorRepository.updateMonitor(monitor);
        scopes.when(() -> ScopeResolver.strictGroupChannelIds(group)).thenReturn(Set.of(ALLOWED, RESTRICTED));
        assertTrue(tree(reader.getMonitors()).isEmpty());
        JsonNode coverage = tree(reader.getDashboardCoverage());
        assertFalse(coverage.path("coversAll").asBoolean());
        assertEquals(1, coverage.path("channelIds").size());
        assertEquals(ALLOWED, coverage.path("channelIds").get(0).asText());
        monitor.setEnabled(false);
        MonitorRepository.updateMonitor(monitor);
        assertTrue(tree(reader.getDashboardCoverage()).path("channelIds").isEmpty());
    }

    @Test
    void coverageFailsClosedOnUnknownScopeMembershipAndRetriesWithoutCachingFailure() throws Exception {
        String group = "00000000-0000-0000-0000-000000000003";
        monitor.setScopeType(ScopeType.GROUP);
        monitor.setScopeId(group);
        MonitorRepository.updateMonitor(monitor);
        scopes.when(() -> ScopeResolver.strictGroupChannelIds(group))
                .thenThrow(new IllegalStateException("synthetic membership failure"));
        assertStatus(500, reader::getDashboardCoverage);
        scopes.when(() -> ScopeResolver.strictGroupChannelIds(group)).thenReturn(Set.of(ALLOWED));
        assertEquals(ALLOWED, tree(reader.getDashboardCoverage()).path("channelIds").get(0).asText());
    }

    private Monitor duplicateMonitor(String name, String scope) {
        Monitor other = Json.read(Json.write(monitor), Monitor.class);
        other.setName(name);
        other.setScopeType(ScopeType.CHANNEL);
        other.setScopeId(scope);
        return MonitorRepository.insertMonitor(other);
    }

    private AlertEvent insertEvent(int monitorId, String channelId, Integer actor) {
        AlertEvent another = Json.read(Json.write(event), AlertEvent.class);
        another.setMonitorId(monitorId);
        another.setChannelId(channelId);
        another.setAcknowledgedBy(actor);
        if (actor != null) another.setAcknowledgedTime(Instant.parse("2026-10-02T00:00:00Z"));
        return AlertEventRepository.insertAlertEvent(another);
    }

    private static Set<Integer> userIds(JsonNode users) {
        Set<Integer> ids = new HashSet<>();
        for (JsonNode user : users) {
            assertEquals(2, user.size());
            ids.add(user.path("userId").asInt());
        }
        return ids;
    }

    private class DirectoryFixture implements AutoCloseable {
        final MockedStatic<ControllerFactory> factory = mockStatic(ControllerFactory.class);
        final UserController users = mock(UserController.class);
        final Operation original;
        DirectoryFixture() throws Exception {
            ControllerFactory host = mock(ControllerFactory.class);
            factory.when(ControllerFactory::getFactory).thenReturn(host);
            when(host.createUserController()).thenReturn(users);
            List<User> accounts = new ArrayList<>();
            for (int id : List.of(42, 9, 77, 99)) {
                User user = new User(); user.setId(id); user.setUsername("test-user-" + id);
                user.setEmail("private-" + id + "@example.invalid"); accounts.add(user);
            }
            when(users.getAllUsers()).thenReturn(accounts);
            reader.setOperation(new ExtensionOperation(SentinelServletInterface.PLUGIN_POINT,
                    OperationUtil.getOperation(SentinelServletInterface.class, "getCoreUsers")));
            original = reader.currentOperation();
        }
        public void close() { factory.close(); }
    }

    protected JsonNode problems(String channelId, String type) throws Exception {
        return tree(reader.getProblems(null, null, channelId, monitor.getId(), type,
                null, null, null, null, null, null, 0, 25));
    }

    protected static ChannelInfo channel(String id) {
        ChannelInfo channel = new ChannelInfo(); channel.setChannelId(id); return channel;
    }

    protected static JsonNode tree(String body) throws Exception { return Json.mapper().readTree(body); }

    protected static void assertStatus(int code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(MirthApiException.class, action).getResponse().getStatus());
    }

    public static class ReaderServlet extends SentinelServlet {
        Set<String> allowed;
        boolean returnNull;
        boolean directoryAllowed;
        RuntimeException directoryFailure;
        public ReaderServlet() { super(null, null); }
        @Override protected boolean doesUserHaveChannelRestrictions() { return true; }
        @Override protected boolean isChannelRedacted(String id) { return !allowed.contains(id); }
        @Override protected int getCurrentUserId() { return 42; }
        @Override protected boolean isUserAuthorized() {
            if (directoryFailure != null) throw directoryFailure;
            return directoryAllowed;
        }
        com.mirth.connect.client.core.Operation currentOperation() { return operation; }
        @Override protected Set<String> redactChannelIds(Set<String> ids) {
            if (returnNull) return null;
            Set<String> visible = new HashSet<>(ids); visible.retainAll(allowed); return visible;
        }
    }
}
