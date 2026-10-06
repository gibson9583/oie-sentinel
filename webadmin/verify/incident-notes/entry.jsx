import React from 'react';import {createRoot} from 'react-dom/client';import {IncidentTimeline} from '../../web/incident-timeline.jsx';
window.renderIncident=(id=42)=>root.render(<IncidentTimeline key={id} id={id}/>);const root=createRoot(document.getElementById('root'));window.renderIncident();
