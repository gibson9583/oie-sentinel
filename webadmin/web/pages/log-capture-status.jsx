// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// The serving node's log capture for Log monitors, shown on the Dashboard
// under the collector and evaluator health line. The server sends it
// (summary.logPipeline) to users without channel restrictions only.

import { platform } from '@oie/web-shell';
import { fmtNum } from '../ui.jsx';

const React = platform.React;

/** Queue, drops, matcher and log hook of one node, and why it is not counting. */
export function LogCaptureStatus({ status }) {
    const s = status || {};
    const counting = !s.notCountingReason;
    const dropped = Number(s.droppedSinceStart) || 0;
    return (
        <div className="sn-hint sn-log-capture"
            style={{ display: 'flex', gap: 18, flexWrap: 'wrap', alignItems: 'center', marginTop: 6 }}>
            <span className="status-cell">
                <span className={`pip ${counting ? 'ok' : 'warn'}`} />
                <span style={counting ? undefined : { color: 'var(--warn)' }}>
                    {counting ? 'Log capture is counting' : `Log capture is not counting: ${s.notCountingReason}`}
                </span>
            </span>
            <span>Node <span className="mono">{s.nodeId || '?'}</span></span>
            <span>Queue {fmtNum(s.queueDepth)} of {fmtNum(s.queueCapacity)} lines</span>
            <span style={dropped > 0 ? { color: 'var(--warn)' } : undefined}>
                {fmtNum(dropped)} lines dropped since start
            </span>
            <span>Log matcher {s.matcherAlive ? 'running' : 'not running'}</span>
            <span>Log hook {s.appenderAttached ? 'attached' : 'not attached'}</span>
        </div>
    );
}
