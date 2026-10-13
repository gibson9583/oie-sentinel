/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mirth.connect.server.util.SqlConfig;
import java.util.*;
import java.util.stream.IntStream;
import org.apache.ibatis.session.SqlSessionManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class AcknowledgingUserBatchTest {
    @Test
    void callerMutationDuringFirstQueryCannotBroadenLaterBatches() {
        List<String> channels = new ArrayList<>(IntStream.range(0, 2101).mapToObj(i -> "allowed-" + i).toList());
        Set<String> expected = new HashSet<>(channels);
        Set<String> queried = new HashSet<>();
        try (Fixture f = new Fixture()) {
            when(f.sessions.selectList(eq("Sentinel.listAcknowledgingUserIds"), any())).thenAnswer(call -> {
                Map<String, Object> params = call.getArgument(1);
                List<?> batch = (List<?>) params.get("channelIdIn");
                assertTrue(batch.size() <= 1000);
                for (Object id : batch) queried.add((String) id);
                channels.clear(); channels.add("forbidden");
                return List.of(9);
            });
            assertEquals(List.of(9), AlertEventRepository.listAcknowledgingUserIds(channels));
            assertEquals(expected, queried);
            assertFalse(queried.contains("forbidden"));
        }
    }

    @Test
    void emptyAuthorizationNeverAccessesSql() {
        try (MockedStatic<SqlConfig> config = mockStatic(SqlConfig.class)) {
            assertTrue(AlertEventRepository.listAcknowledgingUserIds(List.of()).isEmpty());
            config.verifyNoInteractions();
        }
    }

    @Test
    void unrestrictedInternalLookupPreservesOneGlobalQuery() {
        try (Fixture f = new Fixture()) {
            when(f.sessions.selectList(eq("Sentinel.listAcknowledgingUserIds"), any())).thenAnswer(call -> {
                Map<String, Object> params = call.getArgument(1);
                assertTrue(params.containsKey("channelIdIn"));
                assertNull(params.get("channelIdIn"));
                return List.of(9, 77);
            });
            assertEquals(List.of(9, 77), AlertEventRepository.listAcknowledgingUserIds(null));
            verify(f.sessions, times(1)).selectList(eq("Sentinel.listAcknowledgingUserIds"), any());
        }
    }

    private static class Fixture implements AutoCloseable {
        final MockedStatic<SqlConfig> config = mockStatic(SqlConfig.class);
        final SqlSessionManager sessions = mock(SqlSessionManager.class);
        Fixture() {
            SqlConfig engine = mock(SqlConfig.class);
            config.when(SqlConfig::getInstance).thenReturn(engine);
            when(engine.getSqlSessionManager()).thenReturn(sessions);
        }
        @Override public void close() { config.close(); }
    }
}
