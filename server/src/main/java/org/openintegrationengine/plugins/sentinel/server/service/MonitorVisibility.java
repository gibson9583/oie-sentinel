/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mirth.connect.model.Channel;
import com.mirth.connect.server.controllers.ChannelController;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Authorization of whole monitor definitions, independent of deployment state. */
public final class MonitorVisibility {
    private static final Logger log = LoggerFactory.getLogger(MonitorVisibility.class);

    private MonitorVisibility() {
    }

    /**
     * A restricted reader must be authorized for every member of a known,
     * nonempty scope. Never use evaluator target resolution here: it can
     * omit stopped, deleted, or temporarily unreadable members. A null
     * authorized set denotes a trusted unrestricted caller.
     */
    public static boolean isVisible(Monitor monitor, Set<String> authorizedChannelIds) {
        if (monitor == null) return false;
        if (authorizedChannelIds == null) return true;
        if (authorizedChannelIds.isEmpty() || monitor.getScopeType() == null) return false;
        ScopeType type = monitor.getScopeType();
        String scopeId = monitor.getScopeId();
        if (type != ScopeType.ALL && (scopeId == null || scopeId.isBlank())) return false;
        if (type == ScopeType.ALL && scopeId != null && !scopeId.isBlank()) return false;

        try {
            Set<String> members = switch (type) {
                case CHANNEL -> Set.of(scopeId);
                case GROUP -> ScopeResolver.strictGroupChannelIds(scopeId);
                case TAG -> ScopeResolver.strictTagChannelIds(scopeId);
                case ALL -> allChannelIds();
            };
            if (members == null || members.isEmpty()) return false;
            for (String member : members) {
                if (member == null || member.isBlank() || !authorizedChannelIds.contains(member)) return false;
            }
            return true;
        } catch (Exception e) {
            // Failure to establish the complete scope never grants access.
            log.warn("Could not establish visibility of monitor {}", monitor.getId(), e);
            return false;
        }
    }

    private static Set<String> allChannelIds() {
        List<Channel> channels = ChannelController.getInstance().getChannels(null);
        if (channels == null) throw new IllegalStateException("Channel inventory is unavailable");
        Set<String> ids = new HashSet<>();
        for (Channel channel : channels) {
            String id = channel != null ? channel.getId() : null;
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("Channel inventory contains an unidentified member");
            }
            ids.add(id);
        }
        return ids;
    }
}
