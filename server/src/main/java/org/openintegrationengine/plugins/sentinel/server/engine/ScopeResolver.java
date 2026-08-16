/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.donkey.model.channel.DeployedState;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ChannelGroup;
import com.mirth.connect.model.ChannelTag;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EngineController;

import org.openintegrationengine.plugins.sentinel.shared.model.ChannelGroupInfo;
import org.openintegrationengine.plugins.sentinel.shared.model.ChannelInfo;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;
import org.openintegrationengine.plugins.sentinel.shared.model.TagInfo;

/**
 * Resolves monitor scopes (ALL / GROUP / TAG / CHANNEL) to concrete channels
 * and answers channel/group/tag lookups for the REST layer, entirely from the
 * engine's in-memory caches — no Sentinel table knows what channels exist,
 * so every evaluator tick and every scope-picker request comes through here.
 *
 * <p>"Started" deliberately means {@code DeployedState.STARTED} exactly, not
 * merely "deployed": a paused or stopped channel's source connector is not
 * polling, so evaluating an INACTIVITY or volume monitor against it would be
 * a guaranteed false positive. State comes from
 * {@code EngineController.getDeployedChannel(id).getCurrentState()} (the
 * donkey runtime channel, a pure in-memory read) rather than
 * {@code getChannelStatusList}, which performs channel-revision and
 * code-template DB queries on every call — too expensive for something the
 * evaluator does per monitor per tick.</p>
 *
 * <p>Enum comparisons use {@code ==}: {@code DeployedState.toString()} is
 * overridden to capitalized display text ("Started"), so string comparison
 * against {@code name()} output is a latent bug this class must not
 * introduce. Stateless static utility (private constructor) per the plugin's
 * house style — all state lives in the engine's controllers.</p>
 */
public final class ScopeResolver {

    private static final Logger log = LoggerFactory.getLogger(ScopeResolver.class);

    private ScopeResolver() {
    }

    /**
     * One concrete channel a monitor applies to, with its display name
     * pre-resolved so evaluators and alert payloads never repeat the
     * channel-cache lookup.
     */
    public static final class ChannelTarget {

        public final String channelId;
        public final String channelName;

        public ChannelTarget(String channelId, String channelName) {
            this.channelId = channelId;
            this.channelName = channelName;
        }
    }

    /**
     * Resolves a monitor's scope to the channels it should evaluate this
     * tick: only channels currently in {@code STARTED} state (see class
     * Javadoc for why paused/stopped channels are excluded).
     *
     * <ul>
     *   <li>{@code ALL} — every started channel on the server.</li>
     *   <li>{@code GROUP} — the group's members that are started; an unknown
     *       group yields an empty list (the group may have been deleted after
     *       the monitor was created — that is a configuration drift the
     *       evaluator reports as "no targets", not an error).</li>
     *   <li>{@code TAG} — the started channels bearing the tag; an unknown
     *       tag yields an empty list, same drift policy as GROUP.</li>
     *   <li>{@code CHANNEL} — a singleton if that channel is started, else
     *       empty.</li>
     * </ul>
     *
     * @param monitor the monitor whose scope to resolve
     * @return the started channels in scope; empty (never {@code null}) when
     *         none qualify or the monitor has no usable scope
     */
    public static List<ChannelTarget> resolveStartedChannels(Monitor monitor) {
        List<ChannelTarget> targets = new ArrayList<>();
        ScopeType scopeType = monitor != null ? monitor.getScopeType() : null;
        if (scopeType == null) {
            // Validation prevents this on stored monitors, but the evaluator
            // must never NPE on a malformed row — an empty target list simply
            // skips the monitor for this tick.
            log.warn("Monitor {} has no scope type; resolving to no channels",
                    monitor != null ? monitor.getId() : null);
            return targets;
        }

        switch (scopeType) {
            case ALL:
                EngineController engineController = ControllerFactory.getFactory().createEngineController();
                for (String channelId : engineController.getDeployedIds()) {
                    if (isChannelStarted(channelId)) {
                        targets.add(new ChannelTarget(channelId, channelName(channelId)));
                    }
                }
                break;
            case GROUP:
                for (String channelId : groupChannelIds(monitor.getScopeId())) {
                    if (isChannelStarted(channelId)) {
                        targets.add(new ChannelTarget(channelId, channelName(channelId)));
                    }
                }
                break;
            case TAG:
                for (String channelId : tagChannelIds(monitor.getScopeId())) {
                    if (isChannelStarted(channelId)) {
                        targets.add(new ChannelTarget(channelId, channelName(channelId)));
                    }
                }
                break;
            case CHANNEL:
                String channelId = monitor.getScopeId();
                if (channelId != null && isChannelStarted(channelId)) {
                    targets.add(new ChannelTarget(channelId, channelName(channelId)));
                }
                break;
        }
        return targets;
    }

    /**
     * Resolves a monitor's scope to every channel it names, <em>whatever
     * state those channels are in</em> — the counterpart to
     * {@link #resolveStartedChannels(Monitor)} for the one monitor type whose
     * subject is the state itself.
     *
     * <p>The started gate the other resolver applies is not a detail here, it
     * is the entire difference. A CHANNEL_STATE monitor exists to notice a
     * channel that is stopped, paused, or undeployed; resolving its scope
     * through a filter that drops exactly those channels would make it
     * evaluate only the channels it has nothing to say about. Nothing else in
     * this class changes — group, tag and channel membership resolve
     * identically, and a scope naming a channel that no longer exists still
     * yields nothing, because the id is read from the same live channel
     * cache.</p>
     *
     * <p>Undeployed channels are included, which is why the ALL case walks
     * the channel cache rather than {@code getDeployedIds()}: "undeployed" is
     * one of the states an operator most wants paged about, and the deployed
     * id set is by definition unable to report it.</p>
     *
     * @param monitor the monitor whose scope to resolve
     * @return every channel in scope regardless of state; empty (never
     *         {@code null}) when the monitor has no usable scope
     */
    public static List<ChannelTarget> resolveScopedChannels(Monitor monitor) {
        List<ChannelTarget> targets = new ArrayList<>();
        ScopeType scopeType = monitor != null ? monitor.getScopeType() : null;
        if (scopeType == null) {
            log.warn("Monitor {} has no scope type; resolving to no channels",
                    monitor != null ? monitor.getId() : null);
            return targets;
        }

        switch (scopeType) {
            case ALL:
                List<Channel> channels = ChannelController.getInstance().getChannels(null);
                if (channels != null) {
                    for (Channel channel : channels) {
                        if (channel.getId() != null) {
                            targets.add(new ChannelTarget(channel.getId(), channelName(channel.getId())));
                        }
                    }
                }
                break;
            case GROUP:
                for (String channelId : groupChannelIds(monitor.getScopeId())) {
                    addIfChannelExists(targets, channelId);
                }
                break;
            case TAG:
                for (String channelId : tagChannelIds(monitor.getScopeId())) {
                    addIfChannelExists(targets, channelId);
                }
                break;
            case CHANNEL:
                addIfChannelExists(targets, monitor.getScopeId());
                break;
        }
        return targets;
    }

    /**
     * Adds a channel target only when the id still resolves to a real
     * channel. Group and tag membership can outlive the channel it names, and
     * an undeployed-state monitor must not report a deleted channel as
     * "undeployed" forever — deleted and stopped are different facts and only
     * one of them is an incident.
     */
    private static void addIfChannelExists(List<ChannelTarget> targets, String channelId) {
        if (channelId == null) {
            return;
        }
        Channel channel = ChannelController.getInstance().getChannelById(channelId);
        if (channel != null) {
            targets.add(new ChannelTarget(channelId, channelName(channelId)));
        }
    }

    /**
     * The channel's current runtime state as an uppercase name, using
     * {@code UNDEPLOYED} for a channel the engine holds no deployed instance
     * of — the same vocabulary {@link #listChannels()} reports, so the monitor
     * editor's state picker and the evaluator agree on one spelling.
     *
     * <p>Returns the {@link DeployedState} {@code name()}, never its
     * {@code toString()}: the enum overrides {@code toString()} to
     * capitalized display text ("Started"), and a config matching on that
     * form would silently never fire.</p>
     *
     * @param channelId the OIE channel id
     * @return the state name; never {@code null}
     */
    public static String channelState(String channelId) {
        EngineController engineController = ControllerFactory.getFactory().createEngineController();
        com.mirth.connect.donkey.server.channel.Channel deployed = engineController.getDeployedChannel(channelId);
        DeployedState state = deployed != null ? deployed.getCurrentState() : null;
        return state != null ? state.name() : DeployedState.UNDEPLOYED.name();
    }

    /**
     * True iff the channel is deployed and its runtime state is exactly
     * {@code STARTED}. Reads the donkey runtime channel, which is an
     * in-memory map lookup — cheap enough to call once per candidate channel
     * per evaluator tick.
     *
     * @param channelId the OIE channel id
     */
    public static boolean isChannelStarted(String channelId) {
        EngineController engineController = ControllerFactory.getFactory().createEngineController();
        com.mirth.connect.donkey.server.channel.Channel deployed = engineController.getDeployedChannel(channelId);
        return deployed != null && deployed.getCurrentState() == DeployedState.STARTED;
    }

    /**
     * Resolves a channel id to its display name from the engine's channel
     * cache, falling back to {@code "(unknown)"} for ids that no longer
     * resolve (deleted channels referenced by historical alerts). Never
     * returns {@code null} so callers can embed the result straight into
     * messages and payloads without their own null handling.
     *
     * @param channelId the OIE channel id
     */
    public static String channelName(String channelId) {
        if (channelId == null) {
            return "(unknown)";
        }
        Channel channel = ChannelController.getInstance().getChannelById(channelId);
        if (channel == null || channel.getName() == null) {
            return "(unknown)";
        }
        return channel.getName();
    }

    /**
     * Returns the member channel ids of a channel group, or an empty set if
     * the group id is unknown. Group members from
     * {@code getChannelGroups(null)} are id-only {@code Channel} stubs
     * (the engine strips them via {@code replaceChannelsWithIds}), so only
     * {@code getId()} is read here — a member's {@code getName()} would be
     * {@code null}.
     *
     * @param groupId the channel group id
     * @return member channel ids; empty (never {@code null}) if the group is unknown
     */
    public static Set<String> groupChannelIds(String groupId) {
        Set<String> channelIds = new HashSet<>();
        if (groupId == null) {
            return channelIds;
        }
        List<ChannelGroup> groups = ChannelController.getInstance().getChannelGroups(null);
        if (groups == null) {
            return channelIds;
        }
        for (ChannelGroup group : groups) {
            if (groupId.equals(group.getId())) {
                if (group.getChannels() != null) {
                    for (Channel member : group.getChannels()) {
                        if (member.getId() != null) {
                            channelIds.add(member.getId());
                        }
                    }
                }
                return channelIds;
            }
        }
        return channelIds;
    }

    /**
     * Returns the channel ids bearing a channel tag, or an empty set if the
     * tag id is unknown. Tags live in the engine's configuration controller
     * (an in-memory read); membership is resolved live so tag edits apply to
     * the next evaluation without touching the scoped row — same contract as
     * {@link #groupChannelIds(String)}.
     *
     * @param tagId the engine channel-tag id
     * @return member channel ids; empty (never {@code null}) if the tag is unknown
     */
    public static Set<String> tagChannelIds(String tagId) {
        Set<String> channelIds = new HashSet<>();
        if (tagId == null) {
            return channelIds;
        }
        Set<ChannelTag> tags = ControllerFactory.getFactory().createConfigurationController().getChannelTags();
        if (tags == null) {
            return channelIds;
        }
        for (ChannelTag tag : tags) {
            if (tagId.equals(tag.getId())) {
                if (tag.getChannelIds() != null) {
                    channelIds.addAll(tag.getChannelIds());
                }
                return channelIds;
            }
        }
        return channelIds;
    }

    /**
     * Lists every channel tag for the {@code /core/tags} pickers: id, name,
     * member channel ids, and the tag's background color flattened to a
     * {@code #rrggbb} hex string (see {@link TagInfo} for why the AWT color
     * never crosses the wire).
     *
     * @return all channel tags; never {@code null}
     */
    public static List<TagInfo> listTags() {
        List<TagInfo> infos = new ArrayList<>();
        Set<ChannelTag> tags = ControllerFactory.getFactory().createConfigurationController().getChannelTags();
        if (tags == null) {
            return infos;
        }
        for (ChannelTag tag : tags) {
            TagInfo info = new TagInfo();
            info.setId(tag.getId());
            info.setName(tag.getName());
            info.setChannelIds(tag.getChannelIds() != null
                    ? new ArrayList<>(tag.getChannelIds()) : new ArrayList<>());
            java.awt.Color color = tag.getBackgroundColor();
            info.setColorHex(color != null
                    ? String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue())
                    : null);
            infos.add(info);
        }
        return infos;
    }

    /**
     * Lists every channel on the server for the {@code /core/channels}
     * picker: id + name from the engine's channel cache
     * ({@code getChannels(null)} — no DB hit) with the runtime state name
     * and a started flag from the deployed donkey channel.
     * Channels that are not deployed report state {@code "UNDEPLOYED"} —
     * the same word the {@link DeployedState} enum uses — so the UI has one
     * consistent vocabulary for both deployed and undeployed channels.
     *
     * @return all channels, deployed or not; never {@code null}
     */
    public static List<ChannelInfo> listChannels() {
        List<ChannelInfo> infos = new ArrayList<>();
        EngineController engineController = ControllerFactory.getFactory().createEngineController();

        List<Channel> channels = ChannelController.getInstance().getChannels(null);
        if (channels == null) {
            return infos;
        }
        for (Channel channel : channels) {
            ChannelInfo info = new ChannelInfo();
            info.setChannelId(channel.getId());
            info.setName(channel.getName());

            com.mirth.connect.donkey.server.channel.Channel deployed =
                    engineController.getDeployedChannel(channel.getId());
            DeployedState state = deployed != null ? deployed.getCurrentState() : null;
            info.setState(state != null ? state.name() : DeployedState.UNDEPLOYED.name());
            info.setStarted(state == DeployedState.STARTED);
            infos.add(info);
        }
        return infos;
    }

    /**
     * Lists every channel group for the {@code /core/channelGroups} picker.
     *
     * <p>The implicit "[Default Group]" (channels not assigned to any group)
     * is deliberately NOT synthesized here: {@code getChannelGroups(null)}
     * does not return it, and a GROUP-scoped monitor on "every ungrouped
     * channel" is not a v1 use case — an operator wanting server-wide
     * coverage uses ALL scope instead. Members come back as id-only stubs,
     * which is exactly what the picker needs.</p>
     *
     * @return all explicitly defined channel groups; never {@code null}
     */
    public static List<ChannelGroupInfo> listGroups() {
        List<ChannelGroupInfo> infos = new ArrayList<>();
        List<ChannelGroup> groups = ChannelController.getInstance().getChannelGroups(null);
        if (groups == null) {
            return infos;
        }
        for (ChannelGroup group : groups) {
            ChannelGroupInfo info = new ChannelGroupInfo();
            info.setId(group.getId());
            info.setName(group.getName());

            // LinkedHashSet: dedupe defensively while preserving the group's
            // member order for a stable picker display.
            Set<String> memberIds = new LinkedHashSet<>();
            if (group.getChannels() != null) {
                for (Channel member : group.getChannels()) {
                    if (member.getId() != null) {
                        memberIds.add(member.getId());
                    }
                }
            }
            info.setChannelIds(new ArrayList<>(memberIds));
            infos.add(info);
        }
        return infos;
    }
}
