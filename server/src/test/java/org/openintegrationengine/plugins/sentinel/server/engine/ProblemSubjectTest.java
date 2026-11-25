/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.MaintenanceWindow;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;
import org.openintegrationengine.plugins.sentinel.shared.model.SubjectType;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;

/** The subject rules on their own; every call site that delegates to them is tested where it lives. */
class ProblemSubjectTest {

    @Test
    void factoriesReadTheStoredKindAndIgnoreAChannelIdOnAServerRow() {
        AlertEvent server = new AlertEvent();
        server.setSubjectType(SubjectType.SERVER);
        server.setChannelId("left-by-a-direct-edit");
        assertSame(ProblemSubject.server(), ProblemSubject.of(server));
        assertNull(ProblemSubject.of(server).channelId());

        AlertEvent legacy = new AlertEvent(); // subject type defaults to CHANNEL, as a pre-v14 row reads
        legacy.setChannelId("c-1");
        assertEquals(ProblemSubject.channel("c-1"), ProblemSubject.of(legacy));

        TriggerState state = new TriggerState();
        state.setSubjectType(null); // stored NULL reads as CHANNEL
        state.setChannelId("c-2");
        assertEquals(SubjectType.CHANNEL, ProblemSubject.of(state).type());
        assertEquals("c-2", ProblemSubject.of(state).channelId());
    }

    @Test
    void forMonitorFollowsTheTypesSubjectKind() {
        assertTrue(ProblemSubject.forMonitor(monitor(MonitorType.LOG_EVENTS), "ignored").isServer());
        for (MonitorType type : MonitorType.values()) {
            if (type != MonitorType.LOG_EVENTS) {
                assertEquals(ProblemSubject.channel("c-1"), ProblemSubject.forMonitor(monitor(type), "c-1"), type.name());
            }
        }
        assertEquals(ProblemSubject.channel("c-1"), ProblemSubject.forMonitor(monitor(null), "c-1"));
        assertEquals(ProblemSubject.channel("c-1"), ProblemSubject.forMonitor(null, "c-1"));
    }

    @Test
    void onlyAChannelSubjectWithoutAnIdIsUnidentified() {
        assertTrue(ProblemSubject.server().isIdentified());
        assertTrue(ProblemSubject.channel("c-1").isIdentified());
        assertFalse(ProblemSubject.channel(null).isIdentified());
    }

    @Test
    void displayNameIsLogForTheServerAndTheChannelsNameOtherwise() {
        try (MockedStatic<ScopeResolver> scope = mockStatic(ScopeResolver.class)) {
            scope.when(() -> ScopeResolver.channelName("c-1")).thenReturn("ADT In");
            assertEquals("Log", ProblemSubject.server().displayName());
            assertEquals("ADT In", ProblemSubject.channel("c-1").displayName());
            scope.verify(() -> ScopeResolver.channelName(null), never());
        }
    }

    @Test
    void theServerIsVisibleOnlyToAnUnrestrictedCaller() {
        assertTrue(ProblemSubject.server().visibleTo((Set<String>) null));
        assertFalse(ProblemSubject.server().visibleTo(Set.of("c-1")));
        assertFalse(ProblemSubject.server().visibleTo(Set.of()));

        assertTrue(ProblemSubject.channel("c-1").visibleTo((Set<String>) null));
        assertTrue(ProblemSubject.channel("c-1").visibleTo(Set.of("c-1")));
        assertFalse(ProblemSubject.channel("c-2").visibleTo(Set.of("c-1")));
        // Set.of throws on contains(null); an unidentified channel is simply not visible.
        assertFalse(ProblemSubject.channel(null).visibleTo(Set.of("c-1")));
    }

    @Test
    void thePredicateFormNeverAsksAboutTheServer() {
        AtomicInteger asked = new AtomicInteger();
        assertFalse(ProblemSubject.server().visibleTo(true, id -> {
            asked.incrementAndGet();
            return true;
        }));
        assertTrue(ProblemSubject.server().visibleTo(false, id -> {
            asked.incrementAndGet();
            return false;
        }));
        assertEquals(0, asked.get(), "the engine's redaction check must never see the server's null id");

        List<String> seen = new ArrayList<>();
        assertTrue(ProblemSubject.channel("c-1").visibleTo(false, id -> seen.add(id)));
        assertFalse(ProblemSubject.channel("c-2").visibleTo(true, id -> !seen.add(id)));
        assertEquals(List.of("c-1", "c-2"), seen, "a channel subject always consults the check, as before");
    }

    @Test
    void onlyAnAllScopeWindowCoversTheServer() {
        try (MockedStatic<ScopeResolver> scope = mockStatic(ScopeResolver.class)) {
            scope.when(() -> ScopeResolver.strictGroupChannelIds("g")).thenReturn(Set.of("c-1"));
            scope.when(() -> ScopeResolver.strictTagChannelIds("t")).thenReturn(Set.of("c-1"));

            assertTrue(ProblemSubject.server().coveredBy(window(ScopeType.ALL, null)));
            assertFalse(ProblemSubject.server().coveredBy(window(ScopeType.CHANNEL, "c-1")));
            assertFalse(ProblemSubject.server().coveredBy(window(ScopeType.GROUP, "g")));
            assertFalse(ProblemSubject.server().coveredBy(window(ScopeType.TAG, "t")));
            scope.verify(() -> ScopeResolver.strictGroupChannelIds(anyString()), never());
            scope.verify(() -> ScopeResolver.strictTagChannelIds(anyString()), never());

            ProblemSubject channel = ProblemSubject.channel("c-1");
            assertTrue(channel.coveredBy(window(ScopeType.ALL, null)));
            assertTrue(channel.coveredBy(window(ScopeType.CHANNEL, "c-1")));
            assertFalse(channel.coveredBy(window(ScopeType.CHANNEL, "c-2")));
            assertTrue(channel.coveredBy(window(ScopeType.GROUP, "g")));
            assertTrue(channel.coveredBy(window(ScopeType.TAG, "t")));
            assertFalse(ProblemSubject.channel("c-9").coveredBy(window(ScopeType.GROUP, "g")));
        }
    }

    @Test
    void sameAsComparesKindAndChannelButNotTheConnector() {
        TriggerState server = new TriggerState();
        server.setSubjectType(SubjectType.SERVER);
        TriggerState channel = new TriggerState();
        channel.setChannelId("c-1");
        channel.setMetadataId(3);
        TriggerState nullChannel = new TriggerState(); // a CHANNEL row with no id, not the server

        assertTrue(ProblemSubject.server().sameAs(server));
        assertFalse(ProblemSubject.server().sameAs(channel));
        assertFalse(ProblemSubject.server().sameAs(nullChannel));
        assertFalse(ProblemSubject.server().sameAs(null));
        assertTrue(ProblemSubject.channel("c-1").sameAs(channel));
        assertFalse(ProblemSubject.channel("c-2").sameAs(channel));
        assertFalse(ProblemSubject.channel("c-1").sameAs(server));
    }

    @Test
    void valueSemanticsAndOrderPutChannelsByIdThenTheServer() {
        assertEquals(ProblemSubject.channel("c-1"), ProblemSubject.channel("c-1"));
        assertEquals(ProblemSubject.channel("c-1").hashCode(), ProblemSubject.channel("c-1").hashCode());
        assertNotEquals(ProblemSubject.channel(null), ProblemSubject.server());

        TreeMap<ProblemSubject, String> names = new TreeMap<>();
        names.put(ProblemSubject.server(), "Log");
        names.put(ProblemSubject.channel("b"), "B");
        names.put(ProblemSubject.channel("a"), "A");
        assertEquals(List.of("A", "B", "Log"), new ArrayList<>(names.values()));
        assertEquals("server", ProblemSubject.server().toString());
        assertEquals("channel c-1", ProblemSubject.channel("c-1").toString());
    }

    @Test
    void triggerLookupUsesTheChannelOverloadForChannelsAndTheTypedOneForTheServer() {
        TriggerState channelRow = new TriggerState();
        TriggerState serverRow = new TriggerState();
        try (MockedStatic<TriggerStateRepository> states = mockStatic(TriggerStateRepository.class)) {
            states.when(() -> TriggerStateRepository.getTriggerState(7, "c-1", 2)).thenReturn(channelRow);
            states.when(() -> TriggerStateRepository.getTriggerState(7, SubjectType.SERVER, null, null))
                    .thenReturn(serverRow);

            assertSame(channelRow, ProblemSubject.channel("c-1").loadTriggerState(7, 2));
            assertSame(serverRow, ProblemSubject.server().loadTriggerState(7, null));
            states.verify(() -> TriggerStateRepository.getTriggerState(anyInt(), any(SubjectType.class),
                    any(), any()), times(1));
        }
    }

    private static Monitor monitor(MonitorType type) {
        Monitor monitor = new Monitor();
        monitor.setMonitorType(type);
        return monitor;
    }

    private static MaintenanceWindow window(ScopeType scope, String scopeId) {
        MaintenanceWindow window = new MaintenanceWindow();
        window.setScopeType(scope);
        window.setScopeId(scopeId);
        return window;
    }
}
