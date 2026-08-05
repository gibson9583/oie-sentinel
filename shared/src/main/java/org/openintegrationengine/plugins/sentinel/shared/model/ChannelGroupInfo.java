/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.util.List;

/**
 * A slim view of one engine channel group for Sentinel's group-scope pickers
 * ({@code GET /core/channelGroups}).
 *
 * <p>Carries only what GROUP-scoped monitors and maintenance windows need —
 * the group's identity and its member channel ids — flattened server-side
 * from the engine's channel-group model (whose members are id-only stubs
 * anyway). Channels not assigned to any group are simply absent; Sentinel's
 * v1 pickers do not synthesize a "default group". Response-only; never
 * persisted.</p>
 */
public class ChannelGroupInfo {

    private String id;
    private String name;
    private List<String> channelIds;

    public ChannelGroupInfo() {
    }

    /**
     * @return the engine channel-group id (a UUID string); this is what a
     *         GROUP-scoped monitor stores as its scopeId
     */
    public String getId() {
        return id;
    }

    /**
     * @param id the engine channel-group id
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * @return the group's display name
     */
    public String getName() {
        return name;
    }

    /**
     * @param name the group's display name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return ids of the channels in this group; empty for an empty group
     */
    public List<String> getChannelIds() {
        return channelIds;
    }

    /**
     * @param channelIds ids of the channels in this group
     */
    public void setChannelIds(List<String> channelIds) {
        this.channelIds = channelIds;
    }
}
