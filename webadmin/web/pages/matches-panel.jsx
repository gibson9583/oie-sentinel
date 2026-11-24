// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// The Matches panel of a Log problem or notification, shown in the Problems
// and Notifications detail and the Dashboard's inline problem detail: the
// condition, the count, the window, the count from each node and the matched
// lines when the monitor includes them.
//
// The lines are whatever the engine logged, so every field is a React text
// child and never HTML. Nothing here may use dangerouslySetInnerHTML or
// innerHTML; verify/matches-panel.test.mjs checks that HTML comes out escaped.

import { platform } from '@oie/web-shell';
import { fmtNum, fmtTime, isLogAlert } from '../ui.jsx';

const React = platform.React;

/**
 * The parsed value JSON of a Log alert, or null when the alert is not one or
 * its details have no count (an invalid config records {invalidConfig: true}),
 * so the caller shows its generic value view instead.
 *
 * @param event       the AlertEvent (its detailsJson is a JSON string)
 * @param monitorType the ProblemDetail's monitorType, when it has loaded
 */
export function matchDetailsOf(event, monitorType) {
    if (!event || (monitorType !== 'LOG_EVENTS' && !isLogAlert(event))) return null;
    let v = event.detailsJson;
    if (typeof v === 'string') {
        try { v = JSON.parse(v); } catch (e) { return null; }
    }
    if (!v || typeof v !== 'object' || Array.isArray(v) || typeof v.count !== 'number') return null;
    return v;
}

const LABEL_CELL = { width: 170, whiteSpace: 'nowrap' };
const text = (v) => (v == null ? '' : String(v));

function conditionText(d) {
    if (d.condition === 'EACH') return 'Each occurrence';
    if (d.condition === 'AT_LEAST') return `At least ${fmtNum(d.threshold)}`;
    if (d.condition === 'AT_MOST') return `At most ${fmtNum(d.threshold)}`;
    return null;
}

/* The host's tag colours for the levels that mean something failed. */
function levelClass(level) {
    const l = String(level || '').toUpperCase();
    if (l === 'ERROR' || l === 'FATAL') return 'tag red';
    if (l === 'WARN') return 'tag amber';
    return 'tag';
}

/** One matched line: time, level, node and logger on top, the message below. */
function MatchedLine({ line }) {
    const l = line && typeof line === 'object' ? line : { message: line };
    return (
        <div style={{ padding: '6px 0', borderTop: '1px solid var(--line)' }}>
            <div className="mono" style={{
                display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center', fontSize: 11, marginBottom: 3,
            }}>
                <span title={text(l.time)}>{l.time ? fmtTime(l.time) : '(no time)'}</span>
                <span className={levelClass(l.level)}>{text(l.level) || '?'}</span>
                <span className="text-text-dim" title="Node">{text(l.node)}</span>
                <span className="text-text-dim" title="Logger" style={{ overflowWrap: 'anywhere' }}>
                    {text(l.logger)}
                </span>
            </div>
            <pre className="mono" style={{
                margin: 0, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontSize: 11.5,
            }}>{text(l.message)}</pre>
        </div>
    );
}

function Row({ label, children }) {
    return (
        <tr>
            <td className="text-text-dim" style={LABEL_CELL}>{label}</td>
            <td>{children}</td>
        </tr>
    );
}

/** The condition, count, window, per-node counts and matched lines of a Log alert. */
export function MatchesPanel({ details }) {
    const d = details || {};
    const perNode = d.perNode && typeof d.perNode === 'object' && !Array.isArray(d.perNode)
        ? Object.entries(d.perNode) : [];
    const matches = Array.isArray(d.matches) ? d.matches : null;
    const condition = conditionText(d);
    return (
        <div>
            <table className="dt">
                <tbody>
                    {condition ? <Row label="Condition">{condition}</Row> : null}
                    <Row label="Matching lines">
                        {fmtNum(d.count)}
                        {d.windowSeconds != null ? (
                            <span className="text-text-dim">{` in the last ${fmtNum(d.windowSeconds)} seconds`}</span>
                        ) : null}
                    </Row>
                    {perNode.map(([node, count]) => (
                        <Row key={node} label={<>Node <span className="mono">{node}</span></>}>{fmtNum(count)}</Row>
                    ))}
                    {d.node ? <Row label="Node"><span className="mono">{text(d.node)}</span></Row> : null}
                    {d.lastBreach ? (
                        <Row label="Condition last true">
                            {fmtTime(d.lastBreach)}
                            <span className="text-text-dim"> (a problem that clears after a set time counts from here)</span>
                        </Row>
                    ) : null}
                </tbody>
            </table>
            <div className="sn-tile-label" style={{ margin: '12px 0 2px' }}>Matched lines</div>
            {matches === null ? (
                <div className="sn-hint">
                    This monitor does not include matched items. Turn on Include matched items in alerts
                    on the monitor to see them.
                </div>
            ) : matches.length === 0 ? (
                <div className="sn-hint">No matched lines were kept.</div>
            ) : (
                <>
                    <div className="sn-hint" style={{ marginBottom: 4 }}>The newest matching lines, oldest first.</div>
                    {matches.map((line, i) => <MatchedLine key={i} line={line} />)}
                </>
            )}
        </div>
    );
}
