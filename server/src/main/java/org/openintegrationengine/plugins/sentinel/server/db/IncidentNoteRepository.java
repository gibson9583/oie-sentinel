/* OIE Sentinel. Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.db;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import com.mirth.connect.server.util.SqlConfig;

/** No update/delete statement: the note row is the durable acknowledgement and retry ledger. */
public final class IncidentNoteRepository {
    private IncidentNoteRepository() {}
    public record Note(String id, long eventId, int actorId, Instant createdTime, String text) {}
    public record Page(List<Note> notes, String nextCursor) {}
    private static Note note(Map<String,Object> row) {
        return row == null ? null : new Note((String)row.get("note_id"), ((Number)row.get("alert_event_id")).longValue(),
            ((Number)row.get("actor_id")).intValue(), ((Timestamp)row.get("created_time")).toInstant(), (String)row.get("note_text"));
    }
    public static Note get(String id) {
        return note(SqlConfig.getInstance().getSqlSessionManager().selectOne("Sentinel.getIncidentNote", Map.of("noteId",id)));
    }
    public static Note insert(Note note) {
        SqlConfig.getInstance().getSqlSessionManager().insert("Sentinel.insertIncidentNote",Map.of(
            "note_id",note.id(),"alert_event_id",note.eventId(),"actor_id",note.actorId(),
            "created_time",Timestamp.from(note.createdTime()),"note_text",note.text()));
        return note;
    }
    public static Page list(long eventId, String cursor) {
        Map<String,Object> params=new HashMap<>();params.put("alertEventId",eventId);
        params.put("beforeTime",null);params.put("beforeId",null);
        if(cursor!=null && !cursor.isBlank()) {
            Note before=get(cursor);
            if(before==null || before.eventId()!=eventId)throw new IllegalArgumentException("Invalid note cursor");
            params.put("beforeTime",Timestamp.from(before.createdTime()));params.put("beforeId",before.id());
        }
        List<Map<String,Object>> rows=SqlConfig.getInstance().getSqlSessionManager().selectList("Sentinel.listIncidentNotes",params);
        List<Note> notes=rows.stream().limit(50).map(IncidentNoteRepository::note).toList();
        return new Page(notes,rows.size()>50 ? notes.get(notes.size()-1).id() : null);
    }
}
