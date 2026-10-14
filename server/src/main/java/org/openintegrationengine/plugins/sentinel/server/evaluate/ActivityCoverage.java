/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import java.time.Instant;
import java.util.List;
import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.SentinelScheduler;
import org.openintegrationengine.plugins.sentinel.server.service.SettingsService;

/**
 * Requires persisted observations across the entire range before inferring absence.
 * Cadence uses the applied collector trigger only for observations made after its
 * installation. Restart or cadence changes conservatively defer older windows
 * until the entire range, including its anchor, belongs to the new schedule.
 */
final class ActivityCoverage {
    // The supported 90-day retention at the minimum 10-second cadence needs
    // 777,601 timestamps, plus at most 90 anchor timestamps. Allow headroom for
    // jitter/restarts while bounding total work even for unexpectedly dense data.
    private static final int MAX_SOURCE_POINTS = 1_000_000;
    private ActivityCoverage() { }

    static boolean covers(String channelId, Instant start, Instant end) {
        var settings = SettingsService.get();
        int interval = SentinelScheduler.getInstance().getCollectorIntervalSeconds(start);
        if (interval <= 0 || !start.isBefore(end)
                || start.isBefore(end.minusSeconds(settings.getSampleRetentionDays() * 86400L))) {
            return false;
        }
        // Permit scheduling jitter, but reject a missing full collector tick.
        long tolerance = interval + Math.max(5, interval / 2);
        Instant queryStart = start.minusSeconds(tolerance);
        Instant previous = null;
        int remaining = MAX_SOURCE_POINTS;
        while (remaining > 0) {
            int limit = Math.min(ActivityRepository.SAMPLE_TIME_PAGE_SIZE, remaining);
            List<Instant> times = ActivityRepository.listActivitySampleTimes(
                    channelId, queryStart, end, previous, limit);
            if (times == null || times.size() > limit) return false;
            for (Instant time : times) {
                if (time == null || time.isBefore(queryStart) || time.isAfter(end)) return false;
                if (previous == null) {
                    if (time.isAfter(start)
                            || SentinelScheduler.getInstance().getCollectorIntervalSeconds(time) != interval) {
                        return false;
                    }
                } else if (!time.isAfter(previous) || time.isAfter(previous.plusSeconds(tolerance))) {
                    return false;
                }
                previous = time;
            }
            remaining -= times.size();
            // A full page is not evidence that there are no later gaps. The
            // exact upper bound is conclusive; otherwise read until exhaustion.
            if (end.equals(previous) || times.size() < limit) {
                // An old anchor alone is not evidence for a short window.
                return previous != null && previous.isAfter(start)
                        && !previous.isBefore(end.minusSeconds(tolerance));
            }
        }
        return false;
    }
}
