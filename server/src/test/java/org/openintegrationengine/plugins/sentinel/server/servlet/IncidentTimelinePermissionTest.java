/* OIE Sentinel. Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.servlet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.client.core.api.MirthOperation;
import org.openintegrationengine.plugins.sentinel.server.service.*;
import org.openintegrationengine.plugins.sentinel.shared.SentinelServletInterface;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;

class IncidentTimelinePermissionTest {
    static class Caller extends SentinelServlet {
        boolean restricted,hidden;
        Caller(){super(null,null);}
        @Override protected boolean doesUserHaveChannelRestrictions(){return restricted;}
        @Override protected boolean isChannelRedacted(String channel){return hidden;}
        @Override protected int getCurrentUserId(){return 7;}
    }
    @Test void extensionRegistersReadAndWriteInExistingTiers() throws Exception {
        var read=SentinelServletInterface.class.getMethod("getIncidentTimeline",long.class,String.class).getAnnotation(MirthOperation.class);
        var write=SentinelServletInterface.class.getMethod("appendIncidentNote",long.class,String.class).getAnnotation(MirthOperation.class);
        assertEquals(SentinelServletInterface.PERMISSION_VIEW,read.permission());assertEquals("sentinelGetIncidentTimeline",read.name());
        assertEquals(SentinelServletInterface.PERMISSION_ACKNOWLEDGE,write.permission());assertEquals("sentinelAppendIncidentNote",write.name());
        assertFalse(write.auditable()); // Explicit sanitized audit, no framework body-content duplication.
    }
    @Test void hiddenIncidentCannotReachTimelineOrNoteService() {
        Caller caller=mock(Caller.class,CALLS_REAL_METHODS);caller.restricted=true;caller.hidden=true;
        AlertEvent event=new AlertEvent();event.setId(42L);event.setChannelId("hidden");
        try(var problems=mockStatic(ProblemService.class);var timeline=mockStatic(IncidentTimelineService.class)) {
            problems.when(()->ProblemService.get(42)).thenReturn(event);
            var read=assertThrows(MirthApiException.class,()->caller.getIncidentTimeline(42,null));assertEquals(404,read.getResponse().getStatus());
            var write=assertThrows(MirthApiException.class,()->caller.appendIncidentNote(42,"{}"));assertEquals(404,write.getResponse().getStatus());timeline.verifyNoInteractions();
        }
    }
    @Test void actorIsTakenFromSessionAndMalformedBodyCannotWrite() {
        Caller caller=mock(Caller.class,CALLS_REAL_METHODS);
        String key="00000000-0000-0000-0000-000000000001";
        try(var timeline=mockStatic(IncidentTimelineService.class)) {
            timeline.when(()->IncidentTimelineService.append(42,key,"handoff",7)).thenReturn(Map.of("ok",true));
            assertEquals("{\"ok\":true}",caller.appendIncidentNote(42,"{\"requestId\":\""+key+"\",\"text\":\"handoff\",\"actorId\":99}"));
            timeline.verify(()->IncidentTimelineService.append(42,key,"handoff",7),times(1));
            var invalid=assertThrows(MirthApiException.class,()->caller.appendIncidentNote(42,"{\"requestId\":\""+key+"\",\"text\":true}"));assertEquals(400,invalid.getResponse().getStatus());timeline.verifyNoMoreInteractions();
        }
    }
}
