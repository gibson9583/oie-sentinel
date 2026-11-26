/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.SqlSessionManager;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.mockito.MockedStatic;

import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.util.SqlConfig;

import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.db.SentinelMigrator;
import org.openintegrationengine.plugins.sentinel.shared.SentinelServletInterface;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;

/**
 * An in-memory Derby schema built by the real migrator, behind the shipped
 * Derby mapper, with the engine's {@code SqlConfig} and
 * {@code ConfigurationController} replaced. Settings reads see the mock
 * controller, so a test sets them with {@link #setting(String, int)}.
 */
public final class LogMatchTestDatabase implements AutoCloseable {

    private final String url;
    private final SqlSessionManager sessions;
    private final MockedStatic<SqlConfig> sqlConfig;
    private final MockedStatic<ConfigurationController> controllers;
    private final ConfigurationController controller = mock(ConfigurationController.class);

    public LogMatchTestDatabase() throws Exception {
        url = "jdbc:derby:memory:logMatch" + UUID.randomUUID().toString().replace("-", "");
        controllers = mockStatic(ConfigurationController.class);
        controllers.when(ConfigurationController::getInstance).thenReturn(controller);
        try (Connection connection = DriverManager.getConnection(url + ";create=true")) {
            SentinelMigrator migrator = new SentinelMigrator();
            migrator.setConnection(connection);
            migrator.setDatabaseType("derby");
            migrator.migrate();
        }
        Configuration mapping = new Configuration(new Environment("log-match", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.apache.derby.jdbc.EmbeddedDriver", url, null, null)));
        mapping.setJdbcTypeForNull(JdbcType.VARCHAR); // Engine SqlMapConfig.xml.
        try (InputStream in = getClass().getResourceAsStream("/mapper/derby-sqlmap.xml")) {
            new XMLMapperBuilder(in, mapping, "derby", mapping.getSqlFragments()).parse();
        }
        sessions = SqlSessionManager.newInstance(new SqlSessionFactoryBuilder().build(mapping));
        SqlConfig engine = mock(SqlConfig.class);
        when(engine.getSqlSessionManager()).thenReturn(sessions);
        sqlConfig = mockStatic(SqlConfig.class);
        sqlConfig.when(SqlConfig::getInstance).thenReturn(engine);
    }

    /** Makes {@code SettingsService.get()} report this collector interval. */
    public void collectorIntervalSeconds(int seconds) {
        setting("collector.interval.seconds", seconds);
    }

    /** Makes {@code SettingsService.get()} read this stored property, for example {@code retention.sample.days}. */
    public void setting(String key, int value) {
        when(controller.getProperty(SentinelServletInterface.PLUGIN_POINT, key)).thenReturn(String.valueOf(value));
    }

    /** Inserts an enabled LOG_EVENTS monitor and returns it with its id. */
    public Monitor logMonitor(String name, String configJson) {
        Monitor monitor = new Monitor();
        monitor.setName(name);
        monitor.setMonitorType(MonitorType.LOG_EVENTS);
        monitor.setScopeType(ScopeType.ALL);
        monitor.setSeverity(Severity.HIGH);
        monitor.setConfigJson(configJson);
        monitor.setEnabled(true);
        monitor.setMinConsecutiveBreaches(1);
        monitor.setCreatedTime(Instant.parse("2026-11-21T00:00:00Z"));
        return MonitorRepository.insertMonitor(monitor);
    }

    /** Opens a plain JDBC connection to the same database, for direct reads. */
    public Connection connection() throws SQLException {
        return DriverManager.getConnection(url);
    }

    @Override
    public void close() throws Exception {
        try {
            sqlConfig.close();
            controllers.close();
            if (sessions.isManagedSessionStarted()) sessions.close();
        } finally {
            SQLException dropped = assertThrows(SQLException.class, () -> DriverManager.getConnection(url + ";drop=true"));
            assertEquals("08006", dropped.getSQLState());
        }
    }
}
