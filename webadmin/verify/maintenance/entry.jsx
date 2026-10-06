import React from 'react';
import {createRoot} from 'react-dom/client';
import {ChannelMaintenanceShortcut} from '../../web/channel-maintenance.jsx';
createRoot(document.getElementById('root')).render(<ChannelMaintenanceShortcut channelId="00000000-0000-0000-0000-000000000001" channelName="Payments" />);
