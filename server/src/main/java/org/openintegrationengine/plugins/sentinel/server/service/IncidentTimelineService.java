/* OIE Sentinel. Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** Recorded facts only. Nearby channel events imply temporal grouping, never causality. */
public final class IncidentTimelineService {
    private IncidentTimelineService() {}
    private static AlertEvent require(long id) {
        AlertEvent event=AlertEventRepository.getAlertEvent(id);
        if(event==null)throw new NoSuchElementException("Problem unavailable");
        return event;
    }
    public static Map<String,Object> timeline(long id, String cursor) {
        AlertEvent event=require(id);
        if(event.getChannelId()==null || event.getChannelId().isBlank())throw new IllegalArgumentException("Problem channel unavailable");
        AlertEventFilter filter=ProblemService.buildFilter(null,null,null,null,null,null,
            event.getOpenedTime().minus(24,ChronoUnit.HOURS).toEpochMilli(),event.getOpenedTime().plus(24,ChronoUnit.HOURS).toEpochMilli(),null,"opened_time","DESC",0,25);
        filter.setChannelIdIn(List.of(event.getChannelId()));
        PagedResult<AlertEvent> peers=AlertEventRepository.listAlertEvents(filter);
        List<Map<String,Object>> nearby=new ArrayList<>();
        for(AlertEvent peer:peers.items()) {
            Map<String,Object> row=new LinkedHashMap<>();row.put("id",peer.getId());row.put("openedTime",peer.getOpenedTime());
            row.put("status",peer.getStatus());row.put("message",peer.getMessage());nearby.add(row);
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("event",event);
        result.put("dispatches",ActionDispatchLogRepository.listActionDispatchLogsForEvent(id));
        result.put("notes",IncidentNoteRepository.list(id,cursor));result.put("nearby",nearby);result.put("nearbyTotal",peers.total());
        result.put("readTime",Instant.now());return result;
    }
    public static Map<String,Object> append(long eventId, String requestId, String text, int actorId) {
        AlertEvent event=require(eventId); // Recheck incident at action time; resolved events still accept handoff notes.
        if(requestId==null || !requestId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException("A UUID request ID is required");
        String key=UUID.fromString(requestId).toString();
        String body=text==null?"":text.trim();
        // UTF-8 byte bound fits Oracle's 4000 BYTE and every other vendor's character bound.
        if(body.isEmpty() || body.getBytes(StandardCharsets.UTF_8).length>4000)throw new IllegalArgumentException("Note must contain 1–4000 UTF-8 bytes");
        if(body.codePoints().anyMatch(cp -> cp == 0 || (cp >= 0xD800 && cp <= 0xDFFF)))throw new IllegalArgumentException("Note contains an unsupported text character");
        IncidentNoteRepository.Note existing=IncidentNoteRepository.get(key);
        if(existing!=null)return receipt(match(existing,eventId,actorId,body),true);
        IncidentNoteRepository.Note created=new IncidentNoteRepository.Note(key,eventId,actorId,Instant.now().truncatedTo(ChronoUnit.MILLIS),body);
        try {
            IncidentNoteRepository.insert(created);
            IncidentNoteRepository.Note persisted=IncidentNoteRepository.get(key);
            if(persisted==null)throw new IllegalStateException("Persisted note receipt unavailable");
            created=match(persisted,eventId,actorId,body);
        }
        catch(RuntimeException failure) {
            // Auto-commit may have completed before a connection error, or another request won the PK.
            IncidentNoteRepository.Note committed=IncidentNoteRepository.get(key);
            if(committed!=null)return receipt(match(committed,eventId,actorId,body),true);
            throw failure;
        }
        SentinelAuditLog.problemNoteAdded(actorId,event,key,body.getBytes(StandardCharsets.UTF_8).length);
        return receipt(created,false);
    }
    private static IncidentNoteRepository.Note match(IncidentNoteRepository.Note note,long eventId,int actorId,String text) {
        if(note.eventId()!=eventId || note.actorId()!=actorId || !note.text().equals(text))throw new IllegalArgumentException("Request ID already belongs to a different note");
        return note;
    }
    private static Map<String,Object> receipt(IncidentNoteRepository.Note note,boolean replayed) {return Map.of("note",note,"replayed",replayed);}
}
