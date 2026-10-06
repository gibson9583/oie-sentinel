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
import org.openintegrationengine.plugins.sentinel.server.service.MaintenanceWindowService;
import org.openintegrationengine.plugins.sentinel.server.service.SentinelAuditLog;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class ChannelMaintenancePersistenceTest {
    private String url;
    private SqlSessionFactory factory;
    private MockedStatic<SqlConfig> config;
    private MockedStatic<SentinelAuditLog> audit;
    private final String channel="00000000-0000-0000-0000-000000000001";
    private final long until=Instant.now().plusSeconds(3600).toEpochMilli();
    @BeforeEach void setup() throws Exception {
        url="jdbc:derby:memory:maintenance"+UUID.randomUUID().toString().replace("-","");
        try(Connection c=DriverManager.getConnection(url+";create=true");var controllers=mockStatic(ConfigurationController.class)) {
            controllers.when(ConfigurationController::getInstance).thenReturn(mock(ConfigurationController.class));
            SentinelMigrator migrator=new SentinelMigrator();migrator.setConnection(c);migrator.setDatabaseType("derby");migrator.migrate();
        }
        Configuration cfg=new Configuration(new Environment("test",new JdbcTransactionFactory(),
            new UnpooledDataSource("org.apache.derby.jdbc.EmbeddedDriver",url,null,null)));
        cfg.setJdbcTypeForNull(JdbcType.VARCHAR);
        try(InputStream in=getClass().getResourceAsStream("/mapper/derby-sqlmap.xml")) {new XMLMapperBuilder(in,cfg,"derby",cfg.getSqlFragments()).parse();}
        factory=new SqlSessionFactoryBuilder().build(cfg);
        config=mockStatic(SqlConfig.class);SqlConfig engine=mock(SqlConfig.class);
        when(engine.getSqlSessionManager()).thenReturn(SqlSessionManager.newInstance(factory));config.when(SqlConfig::getInstance).thenReturn(engine);
        audit=mockStatic(SentinelAuditLog.class);
    }
    @AfterEach void cleanup() throws Exception {
        if(audit!=null)audit.close();if(config!=null)config.close();
        if(url!=null) {SQLException dropped=assertThrows(SQLException.class,()->DriverManager.getConnection(url+";drop=true"));assertEquals("08006",dropped.getSQLState());}
    }
    private int count(String table) throws Exception {
        try(Connection c=DriverManager.getConnection(url);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getInt(1);}
    }
    private Map<String,Object> create(String key) {return MaintenanceWindowService.channelMaintenance(key,channel,until,"Repair destination",7);}
    @Test void replaySurvivesCancellationDeletionAndChangedPayload() throws Exception {
        String key=UUID.randomUUID().toString();Map<String,Object> first=create(key);
        MaintenanceWindow window=(MaintenanceWindow)first.get("window");
        assertFalse((Boolean)first.get("replayed"));assertEquals(ScopeType.CHANNEL,window.getScopeType());assertEquals(WindowRepeat.NONE,window.getRepeatType());
        assertEquals(until,window.getActiveUntil().toEpochMilli());
        assertEquals(window.getId(),((MaintenanceWindow)create(key).get("window")).getId());assertEquals(1,count("sentinel_maintenance_window"));
        assertThrows(IllegalArgumentException.class,()->MaintenanceWindowService.channelMaintenance(key,channel,until+1,"Repair destination",7));
        MaintenanceWindowService.cancelChannelMaintenance(key,7);
        assertFalse(((MaintenanceWindow)create(key).get("window")).isEnabled());
        MaintenanceWindowService.delete(window.getId(),7);
        assertTrue((Boolean)create(key).get("removed"));assertEquals(0,count("sentinel_maintenance_window"));assertEquals(1,count("sentinel_maintenance_request"));
        audit.verify(()->SentinelAuditLog.windowCreated(eq(7),any()),times(1));
    }
    @Test void failedScheduleInsertRollsBackRequestClaim() throws Exception {
        try(Connection c=DriverManager.getConnection(url);Statement s=c.createStatement()){s.execute("ALTER TABLE sentinel_maintenance_window ADD CONSTRAINT reject_channel CHECK (scope_type <> 'CHANNEL')");}
        assertThrows(RuntimeException.class,()->create(UUID.randomUUID().toString()));
        assertEquals(0,count("sentinel_maintenance_request"));assertEquals(0,count("sentinel_maintenance_window"));
        audit.verifyNoInteractions();
    }
    @Test void cancellationRejectsScopeChangesWithoutDisablingNewPolicy() {
        String key=UUID.randomUUID().toString();MaintenanceWindow window=(MaintenanceWindow)create(key).get("window");
        window.setScopeType(ScopeType.ALL);window.setScopeId(null);MaintenanceWindowService.update(window.getId(),window,7);
        assertThrows(IllegalArgumentException.class,()->MaintenanceWindowService.cancelChannelMaintenance(key,7));
        assertTrue(MaintenanceWindowService.get(window.getId()).isEnabled());
    }
    @Test void invalidReasonOrExpiryHasNoPersistentEffect() throws Exception {
        for(String reason:List.of(" ","x".repeat(201))) assertThrows(IllegalArgumentException.class,()->MaintenanceWindowService.channelMaintenance(UUID.randomUUID().toString(),channel,until,reason,7));
        assertThrows(IllegalArgumentException.class,()->MaintenanceWindowService.channelMaintenance(UUID.randomUUID().toString(),channel,1,"repair",7));
        assertEquals(0,count("sentinel_maintenance_request"));
    }
    @Test void simultaneousOperatorsUsingSameRequestKeyCreateOneWindow() throws Exception {
        String key=UUID.randomUUID().toString();CountDownLatch gate=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);
        Callable<Integer> call=()->{try(var workerConfig=mockStatic(SqlConfig.class);var workerAudit=mockStatic(SentinelAuditLog.class)){
            SqlConfig engine=mock(SqlConfig.class);when(engine.getSqlSessionManager()).thenReturn(SqlSessionManager.newInstance(factory));workerConfig.when(SqlConfig::getInstance).thenReturn(engine);
            gate.await();return ((MaintenanceWindow)create(key).get("window")).getId();
        }};
        try {Future<Integer> first=workers.submit(call),second=workers.submit(call);gate.countDown();assertEquals(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));}
        finally {workers.shutdownNow();assertTrue(workers.awaitTermination(15,TimeUnit.SECONDS));}
        assertEquals(1,count("sentinel_maintenance_window"));assertEquals(1,count("sentinel_maintenance_request"));
    }
}
