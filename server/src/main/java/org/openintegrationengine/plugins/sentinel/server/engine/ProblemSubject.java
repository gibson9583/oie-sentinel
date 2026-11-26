/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.MaintenanceWindow;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorKind;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;
import org.openintegrationengine.plugins.sentinel.shared.model.SubjectType;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;

/**
 * What one problem or trigger state is about: a channel, or the server as a
 * whole. The rules that depend on that answer (name, visibility, schedule
 * coverage, identity) live here, so callers don't branch on it themselves.
 *
 * <p>A channel subject without a channel id (a synthetic test payload, or a
 * row damaged by a direct edit) is not {@linkplain #isIdentified()
 * identified}, and callers keep their old missing-channel behavior for it.
 * The natural order puts channels first by id, then the server.</p>
 */
public final class ProblemSubject implements Comparable<ProblemSubject> {

    private static final ProblemSubject SERVER = new ProblemSubject(SubjectType.SERVER, null);

    private static final Comparator<ProblemSubject> ORDER = Comparator
            .comparing(ProblemSubject::type)
            .thenComparing(ProblemSubject::channelId, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final SubjectType type;
    private final String channelId;

    private ProblemSubject(SubjectType type, String channelId) {
        this.type = type;
        this.channelId = channelId;
    }

    /** @param channelId {@code null} gives an unidentified channel subject */
    public static ProblemSubject channel(String channelId) {
        return new ProblemSubject(SubjectType.CHANNEL, channelId);
    }

    public static ProblemSubject server() {
        return SERVER;
    }

    /** A server event ignores any channel id a direct edit may have left on the row. */
    public static ProblemSubject of(AlertEvent event) {
        return of(event.getSubjectType(), event.getChannelId());
    }

    public static ProblemSubject of(TriggerState state) {
        return of(state.getSubjectType(), state.getChannelId());
    }

    /**
     * The subject a monitor's evaluation produces: the server for a type whose
     * subject is SERVER, otherwise the given channel. A missing monitor or
     * type reads as a channel monitor.
     */
    public static ProblemSubject forMonitor(Monitor monitor, String channelId) {
        MonitorType monitorType = monitor != null ? monitor.getMonitorType() : null;
        return monitorType != null && monitorType.subjectType() == SubjectType.SERVER
                ? SERVER : channel(channelId);
    }

    private static ProblemSubject of(SubjectType type, String channelId) {
        return SubjectType.orChannel(type) == SubjectType.SERVER ? SERVER : channel(channelId);
    }

    public SubjectType type() {
        return type;
    }

    /** @return the channel id, or {@code null} for the server or an unidentified channel subject */
    public String channelId() {
        return channelId;
    }

    public boolean isServer() {
        return type == SubjectType.SERVER;
    }

    public boolean isIdentified() {
        return isServer() || channelId != null;
    }

    /**
     * @return a channel's current name ({@code "(unknown)"} when deleted); for
     *         the server, "Log"
     */
    public String displayName() {
        if (isServer()) {
            return MonitorKind.LOG.label();
        }
        return ScopeResolver.channelName(channelId);
    }

    /**
     * @param authorizedChannelIds {@code null} for an unrestricted caller
     * @return always for an unrestricted caller; never the server for a
     *         restricted one; a channel only when its id is in the set
     */
    public boolean visibleTo(Set<String> authorizedChannelIds) {
        // The null check before contains: Set.of(...) throws on contains(null).
        return visibleTo(authorizedChannelIds != null,
                id -> authorizedChannelIds == null || (id != null && authorizedChannelIds.contains(id)));
    }

    /**
     * The same rule for a caller whose channel access is a check rather than
     * a set. The server subject never reaches {@code channelReadable}, so no
     * {@code null} channel id is handed to the engine's authorizer.
     */
    public boolean visibleTo(boolean restricted, Predicate<String> channelReadable) {
        return isServer() ? !restricted : channelReadable.test(channelId);
    }

    /**
     * Whether a maintenance window or alerting schedule covers this subject.
     * The server is covered only by ALL scope; a resolver failure propagates
     * so the caller fails the decision closed.
     */
    public boolean coveredBy(MaintenanceWindow window) {
        ScopeType scope = window.getScopeType();
        if (scope == ScopeType.ALL) {
            return true;
        }
        if (isServer() || channelId == null) {
            return false;
        }
        if (scope == ScopeType.CHANNEL) {
            return channelId.equals(window.getScopeId());
        }
        if (scope == ScopeType.GROUP) {
            return ScopeResolver.strictGroupChannelIds(window.getScopeId()).contains(channelId);
        }
        return scope == ScopeType.TAG
                && ScopeResolver.strictTagChannelIds(window.getScopeId()).contains(channelId);
    }

    /** Metadata ids are not compared: a subject owns every connector row of its channel. */
    public boolean sameAs(TriggerState state) {
        return state != null && equals(of(state));
    }

    /**
     * A channel subject uses the channel lookup every channel path has always
     * made; the server uses the typed lookup, which never returns a channel row.
     */
    public TriggerState loadTriggerState(int monitorId, Integer metadataId) {
        return isServer()
                ? TriggerStateRepository.getTriggerState(monitorId, SubjectType.SERVER, null, metadataId)
                : TriggerStateRepository.getTriggerState(monitorId, channelId, metadataId);
    }

    @Override
    public int compareTo(ProblemSubject other) {
        return ORDER.compare(this, other);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProblemSubject subject
                && type == subject.type && Objects.equals(channelId, subject.channelId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, channelId);
    }

    @Override
    public String toString() {
        return isServer() ? "server" : "channel " + channelId;
    }
}
