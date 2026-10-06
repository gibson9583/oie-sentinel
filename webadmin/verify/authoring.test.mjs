import {test} from 'node:test';
import assert from 'node:assert/strict';
import {duplicateMonitor, duplicateSchedule, MONITOR_RECIPES, eligibilityCopy, registerAuthoringGuard, mayLeaveAuthoring} from '../web/authoring.js';
test('copies allowlist configuration, strip identity/audit/secrets and disable', () => {
    const source={id:12,name:'Original',createdTime:'stamp',updatedTime:'stamp',createdBy:'operator',activeNow:true,enabled:true,secret:'redacted',suppressedByMonitorId:13,configJson:'{}',timezone:'UTC',activeFrom:'2026-01-01T00:00:00Z'};
    const monitor=duplicateMonitor(source), schedule=duplicateSchedule(source);
    for(const draft of [monitor,schedule]) { assert.equal(draft.name,'Original (copy)'); assert.equal(draft.enabled,false); for(const k of ['id','createdTime','updatedTime','createdBy','activeNow','secret']) assert.ok(!(k in draft)); }
    assert.equal(monitor.suppressedByMonitorId,13); assert.equal(schedule.timezone,'UTC'); assert.equal(schedule.activeFrom,source.activeFrom); assert.equal(source.enabled,true);
});
test('recipes use existing contracts and deliberate boundaries',()=>{
    assert.deepEqual(MONITOR_RECIPES.map(r=>r.monitorType),['CHANNEL_STATE','INACTIVITY','ERROR_RATE','QUEUE_DEPTH']);
    assert.deepEqual(MONITOR_RECIPES[0].config,{alertOnStates:['STOPPED'],minDurationSeconds:300});
    assert.equal(MONITOR_RECIPES[1].config.noDataForSeconds,1800); assert.equal(MONITOR_RECIPES[2].config.minMessages,100);
    assert.match(eligibilityCopy('CHANNEL_STATE'),/undeployed/); assert.match(eligibilityCopy('CONNECTION_STATUS'),/remote/); assert.match(eligibilityCopy('ERROR_RATE'),/started/);
});
test('guard cleanup cannot remove a newer editor guard',async()=>{
    const old=registerAuthoringGuard(async()=>true); const current=registerAuthoringGuard(async()=>false);
    old(); assert.equal(await mayLeaveAuthoring(),false); current(); assert.equal(await mayLeaveAuthoring(),true);
});
