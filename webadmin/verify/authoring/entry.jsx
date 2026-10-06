import { platform, control } from './host.jsx';
import { MonitorEditor } from '../../web/pages/monitors.jsx';
import { WindowEditor } from '../../web/pages/maintenance.jsx';
import { duplicateMonitor, duplicateSchedule, mayLeaveAuthoring } from '../../web/authoring.js';
const React = platform.React;
const root = platform.ReactDOM.createRoot(document.getElementById('app'));
let version = 0;
let savedDraft = null;
const monitor = { id: 9, name: 'Saved', monitorType: 'ERROR_RATE', configJson: '{"thresholdPercent":5,"minMessages":100,"windowSeconds":300}', scopeType: 'CHANNEL', scopeId: 'c', enabled: true, severity: 'HIGH', minConsecutiveBreaches: 2, suppressedByMonitorId: 10 };
const schedule = { id: 8, name: 'Saved schedule', mode: 'SUPPRESS', scopeType: 'CHANNEL', scopeId: 'c', repeatType: 'WEEKLY', daysOfWeek: 'MONDAY', startTime: '23:00', endTime: '01:00', timezone: 'America/New_York', enabled: true };
window.mount = (kind = 'new') => {
    const common = { key: ++version, channels: [{channelId:'c', name:'ADT'}], groups: [], tags: [], manage: true, onClose: () => { control.closed = true; }, onChanged() {} };
    control.closed = false;
    if (kind.startsWith('schedule')) root.render(<WindowEditor {...common} window={kind === 'schedule-copy' ? duplicateSchedule(schedule) : schedule} onDuplicate={(draft) => { control.duplicate = draft; }} />);
    else root.render(<MonitorEditor {...common} initialDraft={kind === 'resume' ? savedDraft : null} onDraftChange={(draft) => { savedDraft = draft; control.retainedDraft = draft; }} onDraftDiscard={() => { savedDraft = null; control.retainedDraft = null; }} monitor={kind === 'resume' ? monitor : kind === 'edit' ? monitor : kind === 'copy' ? duplicateMonitor(monitor) : null}
        monitors={[monitor, {id:10,name:'Parent'}]} onDuplicate={(draft) => { control.duplicate = draft; }} />);
};
window.mayLeaveAuthoring = mayLeaveAuthoring;
window.mount();
