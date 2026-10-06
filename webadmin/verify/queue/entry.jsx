import {platform,control} from './host.jsx';
import {MonitorEditor} from '../../web/pages/monitors.jsx';
const React=platform.React;
const monitor={id:9,name:'Queue monitor',monitorType:'QUEUE_DEPTH',scopeType:'ALL',enabled:true,severity:'HIGH',minConsecutiveBreaches:2,configJson:'{"threshold":1000,"minDurationSeconds":300}'};
platform.ReactDOM.createRoot(document.getElementById('app')).render(<MonitorEditor monitor={monitor} monitors={[monitor]} channels={[]} groups={[]} tags={[]} manage={true} onClose={()=>{control.closed=true;}} onChanged={()=>{}}/>);
