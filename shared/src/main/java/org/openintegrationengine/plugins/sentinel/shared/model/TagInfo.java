/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.util.List;

/**
 * A slim view of one engine channel tag for Sentinel's tag-scope pickers and
 * condition editors ({@code GET /core/tags}).
 *
 * <p>Mirrors {@link ChannelInfo}/{@link ChannelGroupInfo}: the engine's
 * {@code ChannelTag} carries an AWT {@code Color} the shared module must not
 * depend on, so the background color crosses the wire as a {@code #rrggbb}
 * hex string (or {@code null} when the tag has none). Response-only; never
 * persisted.</p>
 */
public class TagInfo {

    private String id;
    private String name;
    private List<String> channelIds;
    private String colorHex;

    public TagInfo() {
    }

    /**
     * @return the engine channel-tag id (a UUID string)
     */
    public String getId() {
        return id;
    }

    /**
     * @param id the engine channel-tag id
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * @return the tag's display name
     */
    public String getName() {
        return name;
    }

    /**
     * @param name the tag's display name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return ids of the channels bearing this tag; never {@code null}
     */
    public List<String> getChannelIds() {
        return channelIds;
    }

    /**
     * @param channelIds ids of the channels bearing this tag
     */
    public void setChannelIds(List<String> channelIds) {
        this.channelIds = channelIds;
    }

    /**
     * @return the tag's background color as {@code #rrggbb}, or {@code null}
     *         when the tag has no color
     */
    public String getColorHex() {
        return colorHex;
    }

    /**
     * @param colorHex the tag's background color as {@code #rrggbb}, or
     *                 {@code null}
     */
    public void setColorHex(String colorHex) {
        this.colorHex = colorHex;
    }
}
