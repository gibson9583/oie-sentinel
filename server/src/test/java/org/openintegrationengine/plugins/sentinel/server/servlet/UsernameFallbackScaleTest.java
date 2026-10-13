/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.servlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mirth.connect.client.core.ExtensionOperation;
import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.client.core.api.util.OperationUtil;
import com.mirth.connect.model.User;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.UserController;
import com.mirth.connect.server.util.SqlConfig;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.SentinelServletInterface;
import org.openintegrationengine.plugins.sentinel.shared.model.ChannelInfo;

/** Actual fallback service/repository path and shipped vendor SQL, with engine boundaries injected. */
class UsernameFallbackScaleTest {
    @ParameterizedTest
    @ValueSource(strings = {"derby", "mysql", "postgres", "oracle", "sqlserver"})
    void largeFallbackSetsStayWithinVendorLimitsAndPreserveAllAuthorizedAuthors(String vendor) throws Exception {
        checkFallbackSql(vendor, 3101);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 1000, 1001, 2000, 2001, 2101})
    void boundarySizesKeepEveryAuthorizedChannelBound(int count) throws Exception {
        checkFallbackSql("oracle", count);
        checkFallbackSql("sqlserver", count);
    }

    @ParameterizedTest
    @ValueSource(strings = {"derby", "mysql", "postgres", "oracle", "sqlserver"})
    void smallFallbackSetsPreserveTheExistingLookup(String vendor) throws Exception {
        checkFallbackSql(vendor, 7);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    void failedBatchReturnsOpaqueFailureWithoutPartialDirectoryAndRetryReadsEveryChannel(int failureBatch)
            throws Exception {
        try (Fixture f = new Fixture(3101)) {
            var original = f.servlet.currentOperation();
            f.failOnBatch = failureBatch;
            MirthApiException failure = assertThrows(MirthApiException.class, f.servlet::getCoreUsers);
            assertEquals(500, failure.getResponse().getStatus());
            assertFalse(String.valueOf(failure.getResponse().getEntity()).contains("SYNTHETIC_PRIVATE"));
            assertSame(original, f.servlet.currentOperation());
            verifyNoInteractions(f.users);
            assertEquals(failureBatch, f.parameters.size());
            f.failOnBatch = 0;
            f.parameters.clear();
            f.assertResponse();
            assertEquals(f.servlet.allowedChannels, f.queriedChannels());
            assertSame(original, f.servlet.currentOperation());
        }
    }

    @Test
    void authorizationChangesBetweenRequestsDoNotReuseAuthorResults() throws Exception {
        try (Fixture f = new Fixture(3101)) {
            f.assertResponse();
            f.servlet.allowedChannels = Set.of("channel-0");
            f.parameters.clear();
            f.assertResponse();
            assertEquals(Set.of("channel-0"), f.queriedChannels());
            f.servlet.allowedChannels = Set.of();
            f.parameters.clear();
            f.assertResponse();
            assertTrue(f.parameters.isEmpty(), "An empty authorized set must never query unrestricted authors");
        }
    }

    private void checkFallbackSql(String vendor, int count) throws Exception {
        try (Fixture f = new Fixture(count)) {
            f.assertResponse();
            assertEquals(f.servlet.allowedChannels, f.queriedChannels());
            Configuration configuration = new Configuration();
            try (InputStream input = getClass().getResourceAsStream("/mapper/" + vendor + "-sqlmap.xml")) {
                assertNotNull(input);
                new XMLMapperBuilder(input, configuration, vendor, configuration.getSqlFragments()).parse();
            }
            int totalBindings = 0;
            for (Map<String, Object> params : f.parameters) {
                BoundSql sql = configuration.getMappedStatement("Sentinel.listAcknowledgingUserIds").getBoundSql(params);
                assertTrue(sql.getSql().contains("SELECT DISTINCT acknowledged_by"));
                assertTrue(sql.getSql().contains("acknowledged_by IS NOT NULL"));
                var lists = Pattern.compile("channel_id\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE)
                        .matcher(sql.getSql());
                assertTrue(lists.find());
                assertTrue(lists.group(1).split(",").length <= 1000, "Oracle expression limit");
                assertTrue(sql.getParameterMappings().size() <= 1000, "SQL Server per-statement parameter budget");
                assertFalse(sql.getSql().contains("channel-"), "Channel identities must remain bound values");
                totalBindings += sql.getParameterMappings().size();
            }
            assertEquals(count, totalBindings);
            System.out.println("USERNAME_FALLBACK_SCALE vendor=" + vendor + " channels=" + count
                    + " statements=" + f.parameters.size() + " totalBindings=" + totalBindings);
        }
    }

    private static class Fixture implements AutoCloseable {
        final CoreLookupAuthorizationTest.PermissionServlet servlet =
                mock(CoreLookupAuthorizationTest.PermissionServlet.class, CALLS_REAL_METHODS);
        final MockedStatic<ControllerFactory> factory = mockStatic(ControllerFactory.class);
        final MockedStatic<ScopeResolver> scopes = mockStatic(ScopeResolver.class);
        final MockedStatic<SqlConfig> config = mockStatic(SqlConfig.class);
        final UserController users = mock(UserController.class);
        final List<Map<String, Object>> parameters = new ArrayList<>();
        int failOnBatch;

        Fixture(int count) throws Exception {
            servlet.allowedOperations = Set.of(); // Outer monitoring check passed; Manage Users denied.
            servlet.allowedChannels = new LinkedHashSet<>(IntStream.range(0, count)
                    .mapToObj(i -> "channel-" + i).toList());
            servlet.checked = new ArrayList<>();
            servlet.restricted = true;
            servlet.setOperation(new ExtensionOperation(SentinelServletInterface.PLUGIN_POINT,
                    OperationUtil.getOperation(SentinelServletInterface.class, "getCoreUsers")));
            List<ChannelInfo> inventory = new ArrayList<>();
            for (String id : servlet.allowedChannels) {
                ChannelInfo channel = new ChannelInfo(); channel.setChannelId(id); inventory.add(channel);
            }
            ChannelInfo hidden = new ChannelInfo(); hidden.setChannelId("forbidden"); inventory.add(hidden);
            scopes.when(ScopeResolver::listChannels).thenReturn(inventory);
            ControllerFactory host = mock(ControllerFactory.class);
            factory.when(ControllerFactory::getFactory).thenReturn(host);
            when(host.createUserController()).thenReturn(users);
            when(users.getAllUsers()).thenReturn(List.of(user(42), user(99), user(88), user(66), user(77)));
            SqlConfig engine = mock(SqlConfig.class);
            SqlSessionManager sessions = mock(SqlSessionManager.class);
            config.when(SqlConfig::getInstance).thenReturn(engine);
            when(engine.getSqlSessionManager()).thenReturn(sessions);
            when(sessions.selectList(eq("Sentinel.listAcknowledgingUserIds"), any())).thenAnswer(call -> {
                Map<String, Object> params = call.getArgument(1);
                parameters.add(new HashMap<>(params));
                if (failOnBatch == parameters.size()) {
                    throw new IllegalStateException("SYNTHETIC_PRIVATE_BATCH_FAILURE");
                }
                List<?> channels = (List<?>) params.get("channelIdIn");
                List<Integer> authors = new ArrayList<>();
                if (channels.contains("channel-0")) authors.add(99);
                if (channels.contains("channel-1000")) { authors.add(99); authors.add(88); }
                if (channels.contains("channel-3000")) authors.add(66);
                if (channels.contains("forbidden")) authors.add(77);
                return authors;
            });
        }

        void assertResponse() throws Exception {
            var original = servlet.currentOperation();
            var response = Json.mapper().readTree(servlet.getCoreUsers());
            Set<Integer> actual = new HashSet<>();
            for (var user : response) {
                assertEquals(2, user.size());
                assertTrue(actual.add(user.path("userId").asInt()), "Authors must be deduplicated");
            }
            Set<Integer> expected = new HashSet<>(Set.of(42));
            if (servlet.allowedChannels.contains("channel-0")) expected.add(99);
            if (servlet.allowedChannels.contains("channel-1000")) { expected.add(99); expected.add(88); }
            if (servlet.allowedChannels.contains("channel-3000")) expected.add(66);
            assertEquals(expected, actual);
            assertSame(original, servlet.currentOperation());
            assertTrue(servlet.checked.stream().allMatch("getAllUsers"::equals));
        }

        Set<String> queriedChannels() {
            Set<String> queried = new HashSet<>();
            for (Map<String, Object> params : parameters) {
                for (Object id : (List<?>) params.get("channelIdIn")) assertTrue(queried.add((String) id));
            }
            return queried;
        }

        private static User user(int id) {
            User user = new User(); user.setId(id); user.setUsername("synthetic-" + id); return user;
        }
        @Override public void close() { config.close(); scopes.close(); factory.close(); }
    }
}
