// Pure receipt validation shared by the Problems workflow and regression tests.
const STATUSES = new Set(['APPLIED', 'ALREADY_ACKNOWLEDGED', 'ALREADY_RESOLVED',
    'UNAVAILABLE', 'NOT_APPLIED', 'FAILED', 'UNKNOWN']);

export function parseBulkReceipt(response, requested, operation) {
    const ids = [...new Set(requested.map(String))];
    const count = response && response[operation === 'ack' ? 'acknowledged' : 'resolved'];
    const validCount = Number.isInteger(count) && count >= 0 && count <= ids.length;
    const unknown = () => ({ requested: ids.length, applied: null, uncertain: true,
        items: ids.map(id => ({ id, status: 'UNKNOWN' })) });
    if (!response || !validCount) return unknown();
    if (response.receipts === undefined) {
        // Older servers can confirm only a count, not which selected IDs applied.
        return { requested: ids.length, applied: count, uncertain: count !== ids.length,
            items: ids.map(id => ({ id, status: count === ids.length ? 'APPLIED' : 'UNKNOWN' })) };
    }
    if (!Array.isArray(response.receipts) || response.receipts.length !== ids.length) return unknown();
    const seen = new Set();
    for (const r of response.receipts) {
        if (!r || !ids.includes(String(r.id)) || seen.has(String(r.id)) || !STATUSES.has(r.status)) return unknown();
        seen.add(String(r.id));
    }
    if (response.receipts.filter(r => r.status === 'APPLIED').length !== count) return unknown();
    const items = response.receipts.map(r => ({ id: String(r.id), status: r.status }));
    return { requested: ids.length, applied: count, items, uncertain: items.some(r => r.status === 'UNKNOWN') };
}

export const RECEIPT_LABELS = {
    APPLIED: 'Applied', ALREADY_ACKNOWLEDGED: 'Already acknowledged',
    ALREADY_RESOLVED: 'Already resolved', UNAVAILABLE: 'Missing or not permitted',
    NOT_APPLIED: 'Not applied — state changed concurrently', FAILED: 'Failed before write',
    UNKNOWN: 'Uncertain — refresh this problem before retrying',
    OBSERVED: 'Current state checked; this request’s effect cannot be attributed',
};
