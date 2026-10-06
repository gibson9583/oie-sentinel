// Sentinel-owned authoring helpers. No private host wizard imports.
export const MONITOR_RECIPES = [
    { key: 'stopped', name: 'Production channel stopped for 5 minutes', monitorType: 'CHANNEL_STATE', config: { alertOnStates: ['STOPPED'], minDurationSeconds: 300 }, hint: 'Includes stopped and undeployed channels in scope; only STOPPED matches this recipe.' },
    { key: 'quiet', name: 'No traffic for 30 minutes', monitorType: 'INACTIVITY', config: { noDataForSeconds: 1800 }, hint: 'Started channels only. Consider expected quiet periods before enabling.' },
    { key: 'errors', name: 'Error rate at or above 5%', monitorType: 'ERROR_RATE', config: { windowSeconds: 300, thresholdPercent: 5, minMessages: 100 }, hint: 'Started channels; a five-minute window with at least 100 messages avoids low-volume noise.' },
    { key: 'queue', name: 'Sustained queue backlog', monitorType: 'QUEUE_DEPTH', config: { threshold: 1000, minDurationSeconds: 300 }, hint: 'Started channels; depth at or above 1,000 for five minutes. Adjust for your normal backlog.' },
];

// Allowlist configuration fields: never inherit identity, audit stamps, status,
// or future secret-bearing fields. Copies start disabled for deliberate review.
export function duplicateMonitor(source) {
    const { name, description, runbookUrl, monitorType, scopeType, scopeId, severity,
        configJson, minConsecutiveBreaches, suppressedByMonitorId } = source;
    return { name: `${name} (copy)`, description, runbookUrl, monitorType, scopeType,
        scopeId, severity, configJson, minConsecutiveBreaches, suppressedByMonitorId, enabled: false };
}
export function duplicateSchedule(source) {
    const { name, mode, scopeType, scopeId, repeatType, daysOfWeek, daysOfMonth,
        startTime, endTime, timezone, activeFrom, activeUntil } = source;
    return { name: `${name} (copy)`, mode, scopeType, scopeId, repeatType,
        daysOfWeek, daysOfMonth, startTime, endTime, timezone, activeFrom, activeUntil, enabled: false };
}
export function eligibilityCopy(type) {
    if (type === 'CHANNEL_STATE') return 'All configured channels in scope are evaluated, including stopped and undeployed channels.';
    if (type === 'CONNECTION_STATUS') return 'Uses live shared deployment inventory, including paused and remote deployments. Undeployed channels are ineligible; incomplete observations remain unknown.';
    return 'Only started channels in scope are evaluated. Missing activity samples remain insufficient data.';
}

// Only Sentinel-owned navigation uses this guard. The public host router has
// no navigation-guard registration API; do not overwrite its private navGuard.
let leaveGuard = null;
export function registerAuthoringGuard(guard) {
    leaveGuard = guard;
    return () => { if (leaveGuard === guard) leaveGuard = null; };
}
export async function mayLeaveAuthoring() { return !leaveGuard || await leaveGuard(); }
