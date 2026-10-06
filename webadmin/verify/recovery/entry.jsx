import {platform,control} from './host.jsx';
import {MonitorEditor} from '../../web/pages/monitors.jsx';
const React=platform.React;
const monitor={id:9,name:'Error rate',monitorType:'ERROR_RATE',scopeType:'ALL',enabled:true,severity:'HIGH',minConsecutiveBreaches:2,configJson:'{"thresholdPercent":5,"windowSeconds":300,"minMessages":100,"minConsecutiveRecoveries":3}'};
const root=platform.ReactDOM.createRoot(document.getElementById('app'));
root.render(<MonitorEditor monitor={monitor} monitors={[monitor]} channels={[]} groups={[]} tags={[]} manage={true} onClose={()=>{control.closed=true;}} onChanged={()=>{}}/>);
