/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import java.time.Instant;

/**
 * One row of {@code sentinel_match_sample}: what one node counted for a Log or
 * Event monitor since its previous flush. Server-side only.
 *
 * @param id          database id; {@code null} until read back
 * @param nodeId      the node that counted
 * @param monitorId   the monitor the count belongs to
 * @param channelId   the channel the matches belong to, or {@code null} for
 *                    counts not tied to a channel
 * @param sampleTime  when the node flushed
 * @param matchCount  matches since the node's previous flush, not a running
 *                    total
 * @param samplesJson the kept matches as a JSON array, or {@code null} when
 *                    the monitor keeps none
 * @param complete    whether every line in the interval was checked; false
 *                    after dropped lines, a detached appender, a timed-out
 *                    pattern check or a restart
 * @param secondCounts the matches per second of the lines' own time, as a
 *                    JSON object of epoch second to count, for example
 *                    {@code {"1764072000":3}}; only seconds with matches.
 *                    {@code null} when the count is zero, when the lines
 *                    fall in more than 3,600 seconds, and for rows written
 *                    before schema v15
 */
public record MatchSample(Long id, String nodeId, int monitorId, String channelId, Instant sampleTime,
        long matchCount, String samplesJson, boolean complete, String secondCounts) {

    /** A row without per-second counts. */
    public MatchSample(Long id, String nodeId, int monitorId, String channelId, Instant sampleTime,
            long matchCount, String samplesJson, boolean complete) {
        this(id, nodeId, monitorId, channelId, sampleTime, matchCount, samplesJson, complete, null);
    }
}
