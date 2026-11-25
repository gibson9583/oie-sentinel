/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertKind;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;
import org.openintegrationengine.plugins.sentinel.shared.model.SubjectType;

/**
 * Whether an action can receive a server alert: a problem or notification
 * about the engine rather than a channel. Today only Log monitors raise them;
 * a monitor type whose subject is the server is covered as soon as it exists.
 *
 * <p>The real {@link ActionConditionMatcher} decides, over synthetic server
 * alerts: every server monitor type, each existing monitor of that type plus
 * one id no monitor has, every severity, and each event type a condition can
 * see (PROBLEM, RESOLVED and NOTIFICATION). A CHANNEL, CHANNEL_GROUP or
 * CHANNEL_TAG row never matches a server alert, so an action with one never
 * receives them; no conditions, or MONITOR_TYPE and MONITOR rows that let a
 * server monitor through, do.</p>
 *
 * <p>An action also receives server alerts when an action whose conditions
 * let them through escalates to it, directly or along a chain: the dispatcher
 * sends an escalation target the problem whatever the target's own
 * conditions. The chain is followed whole, disabled links included, so the
 * answer can only err towards "can receive".</p>
 */
public final class ServerAlertReach {

    /** What an EVENT_TYPE condition can see; a rollup is matched as the alert it rolls up. */
    private static final List<String> EVENT_TYPES = List.of("PROBLEM", "RESOLVED", "NOTIFICATION");

    /** A monitor id no monitor has, standing for one created later. */
    private static final int UNUSED_MONITOR_ID = -1;

    private final Map<Integer, Action> stored = new HashMap<>();
    private final List<AlertPayload> alerts;

    private ServerAlertReach(Collection<Action> actions, Collection<Monitor> monitors) {
        for (Action action : actions) {
            if (action != null && action.getId() != null) {
                stored.put(action.getId(), action);
            }
        }
        alerts = serverAlerts(monitors);
    }

    /** One read of the stored actions and monitors, for checking several actions against them. */
    public static ServerAlertReach current() {
        return new ServerAlertReach(ActionRepository.listActions(null),
                MonitorRepository.listMonitors(null, null, null, null));
    }

    /** @return {@link #receives(Action)} against every stored action and monitor */
    public static boolean canReceive(Action action) {
        return current().receives(action);
    }

    /** @return {@link #receives(Action)} against these actions and monitors */
    public static boolean canReceive(Action action, Collection<Action> actions, Collection<Monitor> monitors) {
        return new ServerAlertReach(actions, monitors).receives(action);
    }

    /**
     * @param action the action as stored or as it would be saved; with an id,
     *               it replaces the stored action of that id
     * @return whether some server alert would reach the action
     */
    public boolean receives(Action action) {
        if (letsThrough(action, alerts)) {
            return true;
        }
        if (action.getId() == null) {
            return false;
        }
        Map<Integer, Action> byId = new HashMap<>(stored);
        byId.put(action.getId(), action);
        for (Action source : byId.values()) {
            if (!source.getId().equals(action.getId()) && escalatesTo(source, action.getId(), byId)
                    && letsThrough(source, alerts)) {
                return true;
            }
        }
        return false;
    }

    private static boolean letsThrough(Action action, List<AlertPayload> alerts) {
        for (AlertPayload alert : alerts) {
            if (ActionConditionMatcher.matches(action, alert)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code target} is on the escalation chain that starts at {@code source}. */
    private static boolean escalatesTo(Action source, int target, Map<Integer, Action> byId) {
        Set<Integer> visited = new HashSet<>();
        Integer next = source.getEscalateToActionId();
        while (next != null && visited.add(next)) {
            if (next == target) {
                return true;
            }
            Action link = byId.get(next);
            next = link != null ? link.getEscalateToActionId() : null;
        }
        return false;
    }

    private static List<AlertPayload> serverAlerts(Collection<Monitor> monitors) {
        List<AlertPayload> alerts = new ArrayList<>();
        for (MonitorType type : MonitorType.values()) {
            if (type.subjectType() != SubjectType.SERVER) {
                continue;
            }
            Set<Integer> ids = new HashSet<>();
            ids.add(UNUSED_MONITOR_ID);
            for (Monitor monitor : monitors) {
                if (monitor != null && monitor.getId() != null && monitor.getMonitorType() == type) {
                    ids.add(monitor.getId());
                }
            }
            for (int id : ids) {
                Monitor monitor = new Monitor();
                monitor.setId(id);
                monitor.setName("server alert reach");
                monitor.setMonitorType(type);
                for (Severity severity : Severity.values()) {
                    for (String eventType : EVENT_TYPES) {
                        alerts.add(alert(monitor, severity, eventType));
                    }
                }
            }
        }
        return alerts;
    }

    private static AlertPayload alert(Monitor monitor, Severity severity, String eventType) {
        boolean notification = Objects.equals(eventType, "NOTIFICATION");
        AlertEvent event = new AlertEvent();
        event.setId(0L);
        event.setMonitorId(monitor.getId());
        event.setSubjectType(SubjectType.SERVER);
        event.setKind(notification ? AlertKind.NOTIFICATION : AlertKind.PROBLEM);
        event.setSeverity(severity);
        event.setStatus(eventType.equals("PROBLEM") ? AlertStatus.PROBLEM : AlertStatus.RESOLVED);
        return AlertPayload.of(event, monitor, eventType);
    }
}
