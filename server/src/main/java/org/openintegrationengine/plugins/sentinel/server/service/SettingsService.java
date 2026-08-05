/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.server.controllers.ConfigurationController;

import org.openintegrationengine.plugins.sentinel.server.engine.SentinelScheduler;
import org.openintegrationengine.plugins.sentinel.shared.SentinelServletInterface;
import org.openintegrationengine.plugins.sentinel.shared.model.SentinelSettings;

/**
 * Reads and writes Sentinel's tunables (job intervals, retention horizons)
 * in the engine's CONFIGURATION property table, and pushes interval changes
 * into the live scheduler.
 *
 * <p>The CONFIGURATION table (group = the plugin point name) is used instead
 * of a Sentinel table because it is the engine's established home for
 * per-plugin settings, it survives plugin upgrades, and the engine deletes
 * the whole group on uninstall — free cleanup. Values are stored as one
 * property per field rather than a JSON blob so an operator can read and
 * repair them with nothing but a SQL client.</p>
 *
 * <p><b>Why read-back verification:</b> the engine's
 * {@code ConfigurationController.saveProperty} swallows every exception and
 * merely logs (verified engine behavior) — a failed write is silent. Since a
 * silently-lost settings write would leave the scheduler running intervals
 * the operator believes they changed, {@link #update} re-reads each property
 * after saving and logs a warning on mismatch. It still proceeds (the
 * in-memory reschedule uses the requested values), so a transient property
 * write failure degrades to "reverts on restart, with a warning" rather than
 * a hard error for a change that did take effect live.</p>
 *
 * <p>Range limits exist to keep the plugin self-protective: a collector
 * interval below 10s would hammer the statistics API for no monitoring
 * benefit, and a sample retention above 90 days would grow the raw-sample
 * table far past what the hourly trend rollup needs.</p>
 */
public final class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    /** CONFIGURATION property group — must equal the plugin.xml name so uninstall cleans it. */
    private static final String GROUP = SentinelServletInterface.PLUGIN_POINT;

    private static final String KEY_COLLECTOR_INTERVAL = "collector.interval.seconds";
    private static final String KEY_EVALUATOR_INTERVAL = "evaluator.interval.seconds";
    private static final String KEY_SAMPLE_RETENTION = "retention.sample.days";
    private static final String KEY_TREND_RETENTION = "retention.trend.days";
    private static final String KEY_RESOLVED_ALERT_RETENTION = "retention.resolvedalert.days";

    private static final int COLLECTOR_MIN = 10, COLLECTOR_MAX = 600;
    private static final int EVALUATOR_MIN = 30, EVALUATOR_MAX = 3600;
    private static final int SAMPLE_MIN = 1, SAMPLE_MAX = 90;
    private static final int TREND_MIN = 7, TREND_MAX = 3650;
    private static final int RESOLVED_MIN = 7, RESOLVED_MAX = 3650;

    private SettingsService() {
    }

    /**
     * Loads the current settings, falling back to each field's built-in
     * default when its property is missing, unparsable, or out of range.
     * Out-of-range stored values are treated as bad (not clamped) because a
     * hand-edited {@code collector.interval.seconds=1} must not be honored
     * by the scheduler — the defaults are the only always-safe fallback.
     *
     * @return a fully populated settings object; never {@code null}
     */
    public static SentinelSettings get() {
        SentinelSettings defaults = new SentinelSettings();
        SentinelSettings settings = new SentinelSettings();
        settings.setCollectorIntervalSeconds(readInt(KEY_COLLECTOR_INTERVAL,
                defaults.getCollectorIntervalSeconds(), COLLECTOR_MIN, COLLECTOR_MAX));
        settings.setEvaluatorIntervalSeconds(readInt(KEY_EVALUATOR_INTERVAL,
                defaults.getEvaluatorIntervalSeconds(), EVALUATOR_MIN, EVALUATOR_MAX));
        settings.setSampleRetentionDays(readInt(KEY_SAMPLE_RETENTION,
                defaults.getSampleRetentionDays(), SAMPLE_MIN, SAMPLE_MAX));
        settings.setTrendRetentionDays(readInt(KEY_TREND_RETENTION,
                defaults.getTrendRetentionDays(), TREND_MIN, TREND_MAX));
        settings.setResolvedAlertRetentionDays(readInt(KEY_RESOLVED_ALERT_RETENTION,
                defaults.getResolvedAlertRetentionDays(), RESOLVED_MIN, RESOLVED_MAX));
        return settings;
    }

    /**
     * Validates, persists, and applies new settings: each field is saved as
     * its own property (with read-back verification — see class Javadoc),
     * then the live scheduler is retimed in place so interval changes take
     * effect without a restart, then the change is audited.
     *
     * @param settings the requested settings
     * @param userId   the acting user, for the audit event
     * @return the applied settings (the validated input)
     * @throws IllegalArgumentException if any field is outside its allowed
     *                                  range, naming the field and range
     */
    public static SentinelSettings update(SentinelSettings settings, int userId) {
        if (settings == null) {
            throw new IllegalArgumentException("Settings body is required");
        }
        requireRange("collectorIntervalSeconds", settings.getCollectorIntervalSeconds(), COLLECTOR_MIN, COLLECTOR_MAX);
        requireRange("evaluatorIntervalSeconds", settings.getEvaluatorIntervalSeconds(), EVALUATOR_MIN, EVALUATOR_MAX);
        requireRange("sampleRetentionDays", settings.getSampleRetentionDays(), SAMPLE_MIN, SAMPLE_MAX);
        requireRange("trendRetentionDays", settings.getTrendRetentionDays(), TREND_MIN, TREND_MAX);
        requireRange("resolvedAlertRetentionDays", settings.getResolvedAlertRetentionDays(), RESOLVED_MIN, RESOLVED_MAX);

        saveAndVerify(KEY_COLLECTOR_INTERVAL, settings.getCollectorIntervalSeconds());
        saveAndVerify(KEY_EVALUATOR_INTERVAL, settings.getEvaluatorIntervalSeconds());
        saveAndVerify(KEY_SAMPLE_RETENTION, settings.getSampleRetentionDays());
        saveAndVerify(KEY_TREND_RETENTION, settings.getTrendRetentionDays());
        saveAndVerify(KEY_RESOLVED_ALERT_RETENTION, settings.getResolvedAlertRetentionDays());

        SentinelScheduler.getInstance().reschedule(settings);
        SentinelAuditLog.settingsUpdated(userId, settings);
        return settings;
    }

    /**
     * Reads one integer property, returning {@code defaultValue} for a
     * missing, unparsable, or out-of-range stored value (with a warning for
     * the latter two, so a corrupted property is visible in the log rather
     * than silently defaulted forever).
     */
    private static int readInt(String key, int defaultValue, int min, int max) {
        String raw;
        try {
            raw = ConfigurationController.getInstance().getProperty(GROUP, key);
        } catch (Exception e) {
            log.warn("Failed to read Sentinel property '{}'; using default {}", key, defaultValue, e);
            return defaultValue;
        }
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Sentinel property '{}' has unparsable value '{}'; using default {}", key, raw, defaultValue);
            return defaultValue;
        }
        if (value < min || value > max) {
            log.warn("Sentinel property '{}' value {} is outside [{}, {}]; using default {}",
                    key, value, min, max, defaultValue);
            return defaultValue;
        }
        return value;
    }

    /**
     * Saves one property and immediately reads it back, warning on mismatch
     * — the only detection possible given that {@code saveProperty} swallows
     * its own failures (see class Javadoc).
     */
    private static void saveAndVerify(String key, int value) {
        String expected = String.valueOf(value);
        ConfigurationController.getInstance().saveProperty(GROUP, key, expected);
        String readBack = ConfigurationController.getInstance().getProperty(GROUP, key);
        if (!expected.equals(readBack)) {
            log.warn("Sentinel property '{}' failed to persist (wrote '{}', read back '{}'); "
                    + "the new value is applied in memory but will revert on restart", key, expected, readBack);
        }
    }

    /** Rejects an out-of-range field with a message naming the field and its allowed range. */
    private static void requireRange(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max
                    + " (got " + value + ")");
        }
    }
}
