package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class RecoveryProgressServiceTest {
    @Test void filtersBeforeEmittingRowsAndCountsAndDoesNotMutateState() {
        Monitor monitor = new Monitor(); monitor.setId(7); monitor.setConfigJson("{\"minConsecutiveRecoveries\":3}");
        TriggerState visible = new TriggerState(); visible.setChannelId("visible"); visible.setState(TriggerStatus.PROBLEM);
        visible.setOpenAlertEventId(9L); visible.setLastValueJson("{\"sentinelRecovery\":{\"alertEventId\":9,\"healthyCount\":2,\"required\":3}}");
        TriggerState hidden = new TriggerState(); hidden.setChannelId("hidden"); hidden.setState(TriggerStatus.PROBLEM); hidden.setOpenAlertEventId(99L);
        try (var definitions=mockStatic(MonitorService.class);var states=mockStatic(TriggerStateRepository.class);var events=mockStatic(AlertEventRepository.class)) {
            definitions.when(()->MonitorService.get(7)).thenReturn(monitor);
            states.when(()->TriggerStateRepository.listTriggerStatesByMonitor(7)).thenReturn(List.of(hidden,visible));
            AlertEvent event=new AlertEvent();event.setStatus(AlertStatus.PROBLEM);
            events.when(()->AlertEventRepository.getAlertEvent(9L)).thenReturn(event);
            var result=RecoveryProgressService.build(7,Set.of("visible"));
            var rows=(List<?>)result.get("rows"); assertEquals(1,rows.size());
            assertEquals("visible",((Map<?,?>)rows.get(0)).get("channelId"));
            assertEquals(2,((Map<?,?>)rows.get(0)).get("healthyCount"));
            assertEquals(List.of(),RecoveryProgressService.build(7,Set.of()).get("rows"));
            event.setStatus(AlertStatus.RESOLVED);
            result=RecoveryProgressService.build(7,Set.of("visible"));
            assertEquals(0,((Map<?,?>)((List<?>)result.get("rows")).get(0)).get("healthyCount"));
            events.verify(()->AlertEventRepository.getAlertEvent(99L),never());
            states.verify(()->TriggerStateRepository.updateTriggerState(any()),never());
        }
    }
    @Test void capAndTruncationOnlyReflectVisibleRows() {
        Monitor monitor=new Monitor();monitor.setConfigJson("{}");
        var list=new ArrayList<TriggerState>();
        for(int i=0;i<1002;i++){var state=new TriggerState();state.setChannelId(i==0?"hidden":"visible");list.add(state);}
        try(var definitions=mockStatic(MonitorService.class);var states=mockStatic(TriggerStateRepository.class);var events=mockStatic(AlertEventRepository.class)) {
            definitions.when(()->MonitorService.get(7)).thenReturn(monitor);
            states.when(()->TriggerStateRepository.listTriggerStatesByMonitor(7)).thenReturn(list);
            var result=RecoveryProgressService.build(7,Set.of("visible"));
            assertEquals(1000,((List<?>)result.get("rows")).size());assertEquals(true,result.get("truncated"));
            result=RecoveryProgressService.build(7,Set.of("hidden"));
            assertEquals(1,((List<?>)result.get("rows")).size());assertEquals(false,result.get("truncated"));
        }
    }
}
