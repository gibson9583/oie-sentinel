/* OIE Sentinel. Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.InputStream;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.util.SqlConfig;
import org.openintegrationengine.plugins.sentinel.server.service.IncidentTimelineService;
import org.openintegrationengine.plugins.sentinel.server.service.SentinelAuditLog;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class IncidentNotePersistenceTest {
    private String url;private SqlSessionFactory factory;
    private MockedStatic<SqlConfig> config;private MockedStatic<SentinelAuditLog> audit;private MockedStatic<AlertEventRepository> events;
    private AlertEvent event;
    @BeforeEach void setup() throws Exception {
        url="jdbc:derby:memory:notes"+UUID.randomUUID().toString().replace("-","");
        try(Connection c=DriverManager.getConnection(url+";create=true");var controllers=mockStatic(ConfigurationController.class)) {
            controllers.when(ConfigurationController::getInstance).thenReturn(mock(ConfigurationController.class));
            SentinelMigrator migrator=new SentinelMigrator();migrator.setConnection(c);migrator.setDatabaseType("derby");migrator.migrate();
        }
        Configuration cfg=new Configuration(new Environment("test",new JdbcTransactionFactory(),new UnpooledDataSource("org.apache.derby.jdbc.EmbeddedDriver",url,null,null)));
        cfg.setJdbcTypeForNull(JdbcType.VARCHAR);
        try(InputStream in=getClass().getResourceAsStream("/mapper/derby-sqlmap.xml")){new XMLMapperBuilder(in,cfg,"derby",cfg.getSqlFragments()).parse();}
        factory=new SqlSessionFactoryBuilder().build(cfg);config=mockStatic(SqlConfig.class);bind(config);
        audit=mockStatic(SentinelAuditLog.class);events=mockStatic(AlertEventRepository.class);
        event=new AlertEvent();event.setId(42L);event.setChannelId("channel-a");event.setOpenedTime(Instant.parse("2026-10-06T10:00:00Z"));event.setStatus(AlertStatus.PROBLEM);
        events.when(()->AlertEventRepository.getAlertEvent(42L)).thenReturn(event);
    }
    private void bind(MockedStatic<SqlConfig> config) {SqlConfig engine=mock(SqlConfig.class);when(engine.getSqlSessionManager()).thenReturn(SqlSessionManager.newInstance(factory));config.when(SqlConfig::getInstance).thenReturn(engine);}
    @AfterEach void cleanup() throws Exception {
        if(events!=null)events.close();if(audit!=null)audit.close();if(config!=null)config.close();
        if(url!=null){SQLException e=assertThrows(SQLException.class,()->DriverManager.getConnection(url+";drop=true"));assertEquals("08006",e.getSQLState());}
    }
    private Map<String,Object> append(String key,String text,int actor){return IncidentTimelineService.append(42,key,text,actor);}
    @Test void durableReplayAndActorOwnershipAreImmutable() {
        String key=UUID.randomUUID().toString();var first=append(key,"  Handoff\nContinue repair  ",7);
        var note=(IncidentNoteRepository.Note)first.get("note");assertEquals("Handoff\nContinue repair",note.text());assertEquals(7,note.actorId());assertEquals(42,note.eventId());
        assertEquals(note,append(key,"Handoff\nContinue repair",7).get("note"));assertTrue((Boolean)append(key,note.text(),7).get("replayed"));
        assertThrows(IllegalArgumentException.class,()->append(key,"Changed",7));assertThrows(IllegalArgumentException.class,()->append(key,note.text(),8));
        audit.verify(()->SentinelAuditLog.problemNoteAdded(eq(7),same(event),eq(key),anyInt()),times(1));
        assertEquals(1,IncidentNoteRepository.list(42,null).notes().size());
    }
    @Test void rejectedInputOrMissingIncidentWritesNothing() {
        String key=UUID.randomUUID().toString();for(String text:List.of(" ","😀".repeat(1001),"invalid\u0000text","invalid\uD800text"))assertThrows(IllegalArgumentException.class,()->append(key,text,7));
        assertThrows(IllegalArgumentException.class,()->append("invalid","note",7));
        assertThrows(NoSuchElementException.class,()->IncidentTimelineService.append(99,key,"note",7));assertTrue(IncidentNoteRepository.list(42,null).notes().isEmpty());audit.verifyNoInteractions();
    }
    @Test void utf8BoundAndResolvedIncidentNotesRemainValid() {
        event.setStatus(AlertStatus.RESOLVED);String text="😀".repeat(1000);
        assertEquals(text,((IncidentNoteRepository.Note)append(UUID.randomUUID().toString(),text,7).get("note")).text());
    }
    @Test void paginationUsesStableTimestampAndIdentityTiebreaker() {
        Instant now=Instant.parse("2026-10-06T11:00:00Z");
        for(int i=0;i<105;i++)IncidentNoteRepository.insert(new IncidentNoteRepository.Note(String.format("00000000-0000-0000-0000-%012d",i),42,7,now,"note"+i));
        var first=IncidentNoteRepository.list(42,null);assertEquals(50,first.notes().size());assertNotNull(first.nextCursor());
        IncidentNoteRepository.insert(new IncidentNoteRepository.Note(UUID.randomUUID().toString(),42,7,now.plusSeconds(1),"new concurrent note"));
        var second=IncidentNoteRepository.list(42,first.nextCursor());var third=IncidentNoteRepository.list(42,second.nextCursor());assertEquals(50,second.notes().size());assertEquals(5,third.notes().size());assertNull(third.nextCursor());
        Set<String> ids=new HashSet<>();for(var page:List.of(first,second,third))for(var note:page.notes())assertTrue(ids.add(note.id()));assertEquals(105,ids.size());
        assertThrows(IllegalArgumentException.class,()->IncidentNoteRepository.list(43,first.nextCursor()));
        assertThrows(IllegalArgumentException.class,()->IncidentNoteRepository.list(42,UUID.randomUUID().toString()));
    }
    @Test void insertFailureHasNoReceiptOrAuditAndRetryCanSucceed() throws Exception {
        String key=UUID.randomUUID().toString();try(Connection c=DriverManager.getConnection(url);Statement s=c.createStatement()){s.execute("ALTER TABLE sentinel_incident_note ADD CONSTRAINT reject_notes CHECK (actor_id <> 7)");}
        assertThrows(RuntimeException.class,()->append(key,"repair",7));assertNull(IncidentNoteRepository.get(key));audit.verifyNoInteractions();
        try(Connection c=DriverManager.getConnection(url);Statement s=c.createStatement()){s.execute("ALTER TABLE sentinel_incident_note DROP CONSTRAINT reject_notes");}
        assertFalse((Boolean)append(key,"repair",7).get("replayed"));
    }
    @Test void lostAcknowledgementAfterCommitReconcilesOriginalNote() {
        String key=UUID.randomUUID().toString();
        try(var notes=mockStatic(IncidentNoteRepository.class,CALLS_REAL_METHODS)) {
            notes.when(()->IncidentNoteRepository.insert(any())).thenAnswer(call->{call.callRealMethod();throw new RuntimeException("Connection lost after commit");});
            var receipt=append(key,"repair",7);assertTrue((Boolean)receipt.get("replayed"));
            assertEquals(key,((IncidentNoteRepository.Note)receipt.get("note")).id());assertEquals(1,IncidentNoteRepository.list(42,null).notes().size());
        }
        audit.verifyNoInteractions(); // Existing audit is best effort; the immutable note retains actor/time.
    }
    @Test void simultaneousSameRequestCreatesOneNote() throws Exception {
        String key=UUID.randomUUID().toString();CountDownLatch gate=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);
        Callable<String> call=()->{try(var workerConfig=mockStatic(SqlConfig.class);var workerAudit=mockStatic(SentinelAuditLog.class);var workerEvents=mockStatic(AlertEventRepository.class)){
            bind(workerConfig);workerEvents.when(()->AlertEventRepository.getAlertEvent(42L)).thenReturn(event);gate.await();return ((IncidentNoteRepository.Note)append(key,"repair",7).get("note")).id();
        }};
        try{var a=workers.submit(call);var b=workers.submit(call);gate.countDown();assertEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));}
        finally{workers.shutdownNow();assertTrue(workers.awaitTermination(15,TimeUnit.SECONDS));}
        assertEquals(1,IncidentNoteRepository.list(42,null).notes().size());
    }
    @Test void interruptedMigrationRepairsIndexWithoutRecreatingNotes() throws Exception {
        String key=UUID.randomUUID().toString();append(key,"preserved",7);
        try(Connection c=DriverManager.getConnection(url);Statement sql=c.createStatement();var controllers=mockStatic(ConfigurationController.class)) {
            sql.execute("DROP INDEX idx_sentinel_note_event");
            controllers.when(ConfigurationController::getInstance).thenReturn(mock(ConfigurationController.class));
            SentinelMigrator migrator=new SentinelMigrator();migrator.setConnection(c);migrator.setDatabaseType("derby");migrator.migrate();migrator.migrate();
        }
        assertEquals("preserved",IncidentNoteRepository.get(key).text());assertEquals(1,IncidentNoteRepository.list(42,null).notes().size());
    }
    @Test void v15UpgradePreservesMaintenanceTombstonesAndHealthObservations() throws Exception {
        String request="00000000-0000-0000-0000-000000000013";
        try(Connection c=DriverManager.getConnection(url);Statement sql=c.createStatement();var controllers=mockStatic(ConfigurationController.class)) {
            sql.execute("DROP TABLE sentinel_incident_note");
            sql.execute("INSERT INTO sentinel_maintenance_request(request_id,fingerprint,channel_id,window_id) VALUES ('"+request+"','preserved','channel-a',NULL)");
            sql.execute("INSERT INTO sentinel_job_observation(job_name,run_id,node_id,lease_epoch,started_time,outcome) VALUES ('collector','original-run','node-a',15,CURRENT_TIMESTAMP,'SUCCESS')");
            controllers.when(ConfigurationController::getInstance).thenReturn(mock(ConfigurationController.class));
            SentinelMigrator migrator=new SentinelMigrator();migrator.setConnection(c);migrator.setDatabaseType("derby");migrator.migrate();migrator.migrate();
            try(ResultSet rows=sql.executeQuery("SELECT fingerprint,window_id FROM sentinel_maintenance_request WHERE request_id='"+request+"'")){assertTrue(rows.next());assertEquals("preserved",rows.getString(1));assertNull(rows.getObject(2));}
            try(ResultSet rows=sql.executeQuery("SELECT run_id,lease_epoch FROM sentinel_job_observation WHERE job_name='collector'")){assertTrue(rows.next());assertEquals("original-run",rows.getString(1));assertEquals(15,rows.getLong(2));}
            try(ResultSet columns=c.getMetaData().getColumns(null,null,"SENTINEL_ACTION_DISPATCH_LOG","EVENT_PHASE_AT_ATTEMPT")){assertTrue(columns.next());}
        }
        String key=UUID.randomUUID().toString();assertFalse((Boolean)append(key,"after upgrade",7).get("replayed"));assertTrue((Boolean)append(key,"after upgrade",7).get("replayed"));
    }
    @Test void timelineScopeUsesExactChannelAndExplicitTimeWindow() {
        events.when(()->AlertEventRepository.listAlertEvents(any())).thenReturn(new PagedResult<>(List.of(event),1,0,25));
        try(var dispatch=mockStatic(ActionDispatchLogRepository.class)){
            dispatch.when(()->ActionDispatchLogRepository.listActionDispatchLogsForEvent(42)).thenReturn(List.of());
            var result=IncidentTimelineService.timeline(42,null);assertEquals(1L,result.get("nearbyTotal"));
            events.verify(()->AlertEventRepository.listAlertEvents(argThat(f->f.getChannelIdIn().equals(List.of("channel-a")) && f.getPageSize()==25
                && f.getFrom().equals(event.getOpenedTime().minusSeconds(86400)) && f.getTo().equals(event.getOpenedTime().plusSeconds(86400)))));
            dispatch.when(()->ActionDispatchLogRepository.listActionDispatchLogsForEvent(42)).thenThrow(new RepositoryException(new Exception("read failed")));
            assertThrows(RepositoryException.class,()->IncidentTimelineService.timeline(42,null));
        }
    }
}
