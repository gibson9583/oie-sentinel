/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;

/**
 * Which actions a Log alert can reach, decided by the real condition matcher
 * over synthetic server alerts, and through escalation links. No database:
 * the stored actions and monitors are handed in.
 */
class ServerAlertReachTest {

    private static final int LOG_MONITOR = 8;
    private static final int CHANNEL_MONITOR = 9;
    private static final List<Monitor> MONITORS = List.of(monitor(LOG_MONITOR, MonitorType.LOG_EVENTS),
            monitor(CHANNEL_MONITOR, MonitorType.INACTIVITY));

    static Stream<Arguments> conditions() {
        return Stream.of(
                Arguments.of("no conditions", true, null),
                Arguments.of("an empty condition list", true, "[]"),
                Arguments.of("a monitor type that is Log", true, row("MONITOR_TYPE", "=", "'LOG_EVENTS'")),
                Arguments.of("a monitor type that is not Log", true, row("MONITOR_TYPE", "!=", "'INACTIVITY'")),
                Arguments.of("only channel monitor types", false,
                        row("MONITOR_TYPE", "IN", "['INACTIVITY','ERROR_RATE']")),
                Arguments.of("the Log monitor", true, row("MONITOR", "IN", "['9','8']")),
                Arguments.of("only a channel monitor", false, row("MONITOR", "=", "'9'")),
                Arguments.of("any monitor but a channel one", true, row("MONITOR", "!=", "'9'")),
                Arguments.of("a severity", true, row("SEVERITY", ">=", "'DISASTER'")),
                Arguments.of("notifications", true, row("EVENT_TYPE", "=", "'NOTIFICATION'")),
                Arguments.of("resolutions", true, row("EVENT_TYPE", "=", "'RESOLVED'")),
                Arguments.of("a channel", false, row("CHANNEL", "=", "'c-1'")),
                Arguments.of("not a channel", false, row("CHANNEL", "!=", "'c-1'")),
                Arguments.of("a channel group", false, row("CHANNEL_GROUP", "=", "'g-1'")),
                Arguments.of("a channel tag", false, row("CHANNEL_TAG", "IN", "['t-1']")),
                Arguments.of("a channel and the Log type", false, "[" + inner("MONITOR_TYPE", "=", "'LOG_EVENTS'")
                        + "," + inner("CHANNEL", "!=", "'c-1'") + "]"),
                Arguments.of("an unreadable condition", false, "not json"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conditions")
    void conditionsDecideWhetherALogAlertCanReachTheAction(String shape, boolean reachable, String condition) {
        Action action = action(1, condition, null);
        assertEquals(reachable, ServerAlertReach.canReceive(action, List.of(action), MONITORS), shape);
    }

    private static String row(String field, String operator, String value) {
        return "[" + inner(field, operator, value) + "]";
    }

    private static String inner(String field, String operator, String value) {
        return ("{'field':'" + field + "','operator':'" + operator + "','value':" + value + "}").replace('\'', '"');
    }

    @Test
    void aMonitorConditionNamingAMonitorCreatedLaterIsReachableOnlyThroughAnOperatorThatExcludes() {
        Action notOne = action(1, "[{\"field\":\"MONITOR\",\"operator\":\"!=\",\"value\":\"9\"}]", null);
        List<Monitor> channelOnly = List.of(monitor(CHANNEL_MONITOR, MonitorType.INACTIVITY));
        assertEquals(true, ServerAlertReach.canReceive(notOne, List.of(notOne), channelOnly),
                "a Log monitor created later is not monitor 9");
    }

    @Test
    void aChannelScopedActionThatAnUnfilteredActionEscalatesToCanReceiveLogProblems() {
        Action target = action(2, channelCondition(), null);
        Action unfiltered = action(1, null, 2);
        assertEquals(true, ServerAlertReach.canReceive(target, List.of(unfiltered, target), MONITORS));

        Action channelSource = action(1, channelCondition(), 2);
        assertEquals(false, ServerAlertReach.canReceive(target, List.of(channelSource, target), MONITORS),
                "a channel-scoped source never escalates a Log problem");
    }

    @Test
    void anEscalationChainIsFollowedToItsEndAndACycleEnds() {
        Action unfiltered = action(1, null, 3);
        Action middle = action(3, channelCondition(), 2);
        Action target = action(2, channelCondition(), 3);
        assertEquals(true, ServerAlertReach.canReceive(target, List.of(unfiltered, middle, target), MONITORS));
        assertEquals(false, ServerAlertReach.canReceive(action(4, channelCondition(), null),
                List.of(unfiltered, middle, target), MONITORS), "an action off the chain");
    }

    @Test
    void theNewBodyReplacesTheStoredActionOfTheSameId() {
        Action storedUnfiltered = action(1, null, 2);
        Action target = action(2, channelCondition(), null);
        Action editedSource = action(1, channelCondition(), 2);
        assertEquals(false, ServerAlertReach.canReceive(editedSource, List.of(storedUnfiltered, target), MONITORS));
        assertEquals(true, ServerAlertReach.canReceive(target, List.of(storedUnfiltered, target), MONITORS));
        assertEquals(false, ServerAlertReach.canReceive(action(2, channelCondition(), null),
                List.of(editedSource, target), MONITORS), "once the source is channel-scoped");
    }

    private static String channelCondition() {
        return "[{\"field\":\"CHANNEL\",\"operator\":\"=\",\"value\":\"c-1\"}]";
    }

    private static Action action(int id, String condition, Integer escalateTo) {
        Action action = new Action();
        action.setId(id);
        action.setName("action " + id);
        action.setConditionJson(condition);
        action.setEscalateToActionId(escalateTo);
        return action;
    }

    private static Monitor monitor(int id, MonitorType type) {
        Monitor monitor = new Monitor();
        monitor.setId(id);
        monitor.setMonitorType(type);
        return monitor;
    }
}
