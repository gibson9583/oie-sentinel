/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mirth.connect.donkey.model.channel.DeployedState;

import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;

/**
 * Evaluates CHANNEL_STATE monitors: breach when a channel's own deployed
 * state has sat in a configured set — stopped, paused, undeployed — for at
 * least a configured duration.
 *
 * <p>Config JSON: {@code {alertOnStates: [...], minDurationSeconds}}.</p>
 *
 * <h2>Why this type exists</h2>
 *
 * <p>Every other evaluator in this package is handed only channels in
 * {@code DeployedState.STARTED}, and that gate is correct for all of them: a
 * stopped channel's source is not polling, so an INACTIVITY or LOW_VOLUME
 * breach against it would be measuring the operator's own decision. The
 * consequence, though, is that stopping a production channel does not raise
 * an alarm — it silences the alarms that channel already had, and
 * {@code TriggerEvaluatorJob}'s departure sweep closes them with "Channel is
 * no longer started". A monitoring system that goes quiet when a channel
 * stops is reporting the absence of a signal as the absence of a problem.
 * This type is the one that watches the state itself, and it is therefore
 * the one type resolved through
 * {@code ScopeResolver#resolveScopedChannels} rather than the started
 * filter.</p>
 *
 * <h2>Which states, and why the defaults exclude the transitional ones</h2>
 *
 * <p>{@code alertOnStates} holds {@link DeployedState} names. The default —
 * {@code STOPPED}, {@code PAUSED}, {@code UNDEPLOYED} — is the set of
 * <em>resting</em> states a channel does not leave on its own. The
 * transitional states ({@code DEPLOYING}, {@code UNDEPLOYING},
 * {@code STARTING}, {@code STOPPING}, {@code PAUSING}, {@code SYNCING}) are
 * deliberately not in it: every ordinary redeploy walks a channel through
 * several of them, and a default that paged on those would make this monitor
 * fire on routine deployments until the operator learned to ignore it. They
 * remain selectable, because "stuck in STARTING for ten minutes" is a real
 * and nasty condition — that is exactly what {@code minDurationSeconds} is
 * for.</p>
 *
 * <h2>How long the state has held, and why it survives a restart</h2>
 *
 * <p>{@code minDurationSeconds} is what separates a redeploy from a channel
 * someone stopped and forgot. Establishing it needs a memory of when the
 * current state began, and {@link ConnectionStatusEvaluator} answers the same
 * question from {@code CollectorState}'s in-memory since-stamp. That is the
 * wrong place for this one: an in-memory stamp resets on every plugin
 * restart, and "this channel has been stopped since before the last restart"
 * is the single most likely shape of the incident this monitor exists to
 * catch.</p>
 *
 * <p>So the stamp lives in the trigger state row instead. Each evaluation
 * reads the previous {@code last_value_json} for its own
 * {@code (monitor, channel)} pair, and if the state recorded there matches
 * the state observed now, the recorded {@code stateSinceIso} carries forward;
 * otherwise the state just changed and the stamp restarts at {@code now}.
 * The row is written by {@code TriggerEvaluatorJob} on every tick regardless
 * of verdict, so the chain is unbroken, and it is in the database, so it
 * outlives restarts.</p>
 *
 * <p>Two honest limits follow from that. The stamp is a <em>lower bound</em>:
 * the first evaluation of a newly created monitor stamps {@code now}, so a
 * channel stopped for a week reports its duration from the monitor's first
 * tick and takes {@code minDurationSeconds} to breach. And the stamp is
 * per-monitor rather than per-channel, so two CHANNEL_STATE monitors covering
 * one channel each confirm it independently. Both err toward alerting late
 * rather than falsely, which is the correct direction for a monitor whose
 * failure mode would otherwise be paging on every deploy.</p>
 *
 * <h2>No INSUFFICIENT_DATA</h2>
 *
 * <p>Unlike every other evaluator here, this one never returns
 * {@code INSUFFICIENT_DATA}. The others depend on collected history that may
 * not exist yet; a channel's deployed state is a fact the engine can always
 * answer from memory, so there is no "cannot know yet" case to represent. A
 * state that is not in {@code alertOnStates}, or one that has not held long
 * enough, is a genuine OK — which matters, because it is what lets an open
 * problem resolve the moment the channel is started again.</p>
 *
 * <p>Value JSON shape: {@code {"state", "alertOnStates", "sustainedSeconds",
 * "minDurationSeconds", "stateSinceIso"}}.</p>
 */
public final class ChannelStateEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ChannelStateEvaluator.class);

    /**
     * The resting states a channel does not leave without someone acting —
     * see the class Javadoc for why the transitional states are not here.
     */
    private static final Set<String> DEFAULT_ALERT_ON_STATES = Set.of(
            DeployedState.STOPPED.name(), DeployedState.PAUSED.name(), DeployedState.UNDEPLOYED.name());

    /**
     * Default hold before a matching state counts as a breach. Five minutes
     * clears an ordinary redeploy — which passes through STOPPED and
     * UNDEPLOYED on its way back up — without waiting so long that a genuinely
     * stopped channel goes unnoticed through a shift change.
     */
    private static final long DEFAULT_MIN_DURATION_SECONDS = 300L;

    private ChannelStateEvaluator() {
    }

    /**
     * Evaluates one channel against a CHANNEL_STATE monitor.
     *
     * @param monitor      the monitor whose {@code configJson} supplies the
     *                     state set and minimum duration
     * @param channelId    the OIE channel id to evaluate
     * @param currentState the channel's state name now, as
     *                     {@code ScopeResolver#channelState} reports it
     *                     (passed in rather than looked up so the caller reads
     *                     engine state once per channel per tick)
     * @param now          the evaluation instant
     * @return BREACH when the state is in {@code alertOnStates} and has held
     *         for at least {@code minDurationSeconds}, otherwise OK
     */
    public static EvaluationOutcome evaluate(Monitor monitor, String channelId, String currentState, Instant now) {
        JsonNode config = parseConfig(monitor.getConfigJson());
        Set<String> alertOnStates = alertOnStates(config);
        long minDurationSeconds = Math.max(0L,
                config.path("minDurationSeconds").asLong(DEFAULT_MIN_DURATION_SECONDS));

        String state = currentState != null
                ? currentState.trim().toUpperCase(Locale.ROOT)
                : DeployedState.UNDEPLOYED.name();
        Instant since = stateSince(monitor, channelId, state, now);
        long sustainedSeconds = Math.max(0L, Duration.between(since, now).getSeconds());
        String valueJson = buildValueJson(state, alertOnStates, sustainedSeconds, minDurationSeconds, since);

        if (!alertOnStates.contains(state)) {
            return EvaluationOutcome.ok(valueJson);
        }
        if (sustainedSeconds < minDurationSeconds) {
            // Matching but not yet for long enough. A real OK, not a data gap:
            // the state was read and the condition (state AND duration) does
            // not hold, so a problem from an earlier outage may resolve here.
            return EvaluationOutcome.ok(valueJson);
        }
        return EvaluationOutcome.breach(valueJson, String.format(
                "Channel has been %s for %ds", state, sustainedSeconds));
    }

    /**
     * Resolves when the current state began, by reading the stamp this
     * monitor wrote for this channel on the previous tick and carrying it
     * forward only while the state is unchanged.
     *
     * <p>Any failure to read a usable prior stamp — no row yet, unparsable
     * JSON from a hand-edited database, a different state, a malformed
     * timestamp — restarts the clock at {@code now}. That is the safe
     * direction in every one of those cases: it delays a breach by at most
     * {@code minDurationSeconds}, where the alternative (assuming the state
     * is older than it can be shown to be) would fire immediately on a
     * redeploy.</p>
     */
    private static Instant stateSince(Monitor monitor, String channelId, String state, Instant now) {
        TriggerState previous;
        try {
            previous = TriggerStateRepository.getTriggerState(monitor.getId(), channelId, null);
        } catch (Exception e) {
            log.warn("Could not read prior trigger state for monitor {} channel {}; "
                    + "restarting the channel-state clock", monitor.getId(), channelId, e);
            return now;
        }
        if (previous == null || previous.getLastValueJson() == null) {
            return now;
        }
        JsonNode value;
        try {
            value = Json.mapper().readTree(previous.getLastValueJson());
        } catch (Exception e) {
            return now;
        }
        if (!state.equals(value.path("state").asText(null))) {
            return now;   // the state changed; this tick is the new stretch's start
        }
        String sinceIso = value.path("stateSinceIso").asText(null);
        if (sinceIso == null) {
            return now;
        }
        try {
            Instant since = Instant.parse(sinceIso);
            // A stamp in the future (clock adjustment, restored database) must
            // not read as a long-held state; clamp it to now.
            return since.isAfter(now) ? now : since;
        } catch (DateTimeParseException e) {
            return now;
        }
    }

    /**
     * Reads {@code alertOnStates}, normalizing to uppercase names, and falls
     * back to the resting-state default when absent or empty. Unknown names
     * are kept rather than dropped: {@code MonitorService} rejects them at
     * save time, and silently discarding one here would turn a config this
     * build does not recognize into a monitor that quietly watches fewer
     * states than it says it does.
     */
    private static Set<String> alertOnStates(JsonNode config) {
        JsonNode node = config.get("alertOnStates");
        if (node == null || !node.isArray() || node.isEmpty()) {
            return DEFAULT_ALERT_ON_STATES;
        }
        Set<String> states = new LinkedHashSet<>();
        for (JsonNode entry : node) {
            String name = entry.asText("").trim().toUpperCase(Locale.ROOT);
            if (!name.isEmpty()) {
                states.add(name);
            }
        }
        return states.isEmpty() ? DEFAULT_ALERT_ON_STATES : states;
    }

    /**
     * Value JSON per the contract shape. {@code alertOnStates} is echoed back
     * so the problem detail pane can show what the monitor was watching for
     * without re-reading the monitor — the config may have been edited since
     * the alert opened.
     */
    private static String buildValueJson(String state, Set<String> alertOnStates, long sustainedSeconds,
            long minDurationSeconds, Instant since) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("state", state);
        ArrayNode states = node.putArray("alertOnStates");
        for (String name : alertOnStates) {
            states.add(name);
        }
        node.put("sustainedSeconds", sustainedSeconds);
        node.put("minDurationSeconds", minDurationSeconds);
        node.put("stateSinceIso", since.toString());
        return Json.write(node);
    }

    /**
     * Parses the monitor's config JSON, degrading to an empty object (all
     * defaults) on null/blank/malformed input — a broken config on one monitor
     * must produce a defaulted evaluation plus a warning, never an aborted
     * tick. MonitorService validates configs at save time, so this path is
     * defensive.
     */
    private static JsonNode parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return Json.mapper().createObjectNode();
        }
        try {
            return Json.mapper().readTree(configJson);
        } catch (Exception e) {
            log.warn("Unparsable CHANNEL_STATE config '{}'; using defaults", configJson, e);
            return Json.mapper().createObjectNode();
        }
    }
}
