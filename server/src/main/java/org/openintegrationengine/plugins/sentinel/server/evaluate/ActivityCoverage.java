/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.service.SettingsService;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivitySample;

/**
 * Requires persisted observations across the entire range before inferring absence.
 * Cadence uses current collector settings; historical interval changes are not
 * persisted. A shortened interval can conservatively defer an older slow window.
 */
final class ActivityCoverage {
    private static final long MAX_SOURCE_POINTS = 100_000;
    private static final long MIN_COLLECTOR_INTERVAL_MILLIS = 10_000;
    private ActivityCoverage() { }

    static boolean covers(String channelId, Instant start, Instant end) {
        var settings = SettingsService.get();
        int interval = settings.getCollectorIntervalSeconds();
        // A range outside configured raw retention cannot be certified and
        // must not load an arbitrarily large historical sample list.
        if (start.isBefore(end.minusSeconds(settings.getSampleRetentionDays() * 86400L))) {
            return false;
        }
        if (interval <= 0 || !start.isBefore(end)) {
            return false;
        }
        // Permit scheduling jitter, but reject a missing full collector tick.
        long tolerance = interval + Math.max(5, interval / 2);
        Instant queryStart = start.minusSeconds(tolerance);
        // Historic rows may use the minimum10s cadence even if today's
        // setting is slower. Include the anchor slack and both range edges.
        long widthMillis = Duration.between(queryStart, end).toMillis();
        long estimatedRows = 1 + (widthMillis + MIN_COLLECTOR_INTERVAL_MILLIS - 1)
                / MIN_COLLECTOR_INTERVAL_MILLIS;
        if (estimatedRows > MAX_SOURCE_POINTS) return false;
        List<ActivitySample> rows = ActivityRepository.listActivitySamples(channelId, queryStart, end);
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        List<Instant> times = new ArrayList<>();
        for (ActivitySample row : rows) {
            if (row == null || row.getSampleTime() == null) {
                return false;
            }
            Instant time = row.getSampleTime();
            if (!time.isBefore(queryStart) && !time.isAfter(end)) {
                times.add(time);
            }
        }
        times.sort(Comparator.naturalOrder());
        if (times.isEmpty() || times.get(0).isAfter(start)) {
            return false;
        }
        Instant previous = times.get(0);
        for (Instant time : times) {
            if (time.isAfter(previous.plusSeconds(tolerance))) {
                return false;
            }
            previous = time;
        }
        // An old anchor alone is not evidence of a zero-valued short window.
        return previous.isAfter(start) && !previous.isBefore(end.minusSeconds(tolerance));
    }
}
