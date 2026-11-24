/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;

import org.openintegrationengine.plugins.sentinel.server.SentinelServicePlugin;
import org.openintegrationengine.plugins.sentinel.server.alert.ActionDispatcher;
import org.openintegrationengine.plugins.sentinel.server.db.SentinelMigrator;
import org.openintegrationengine.plugins.sentinel.server.engine.SentinelConnectorStatusListener;
import org.openintegrationengine.plugins.sentinel.server.engine.SentinelLeadership;
import org.openintegrationengine.plugins.sentinel.server.engine.SentinelScheduler;

/**
 * The real plugin start and stop against an engine-like log4j context: every
 * other runtime resource is replaced, the log pipeline is not.
 */
class LogPipelineLifecycleTest {

    private static final String READY = String.valueOf(SentinelMigrator.LATEST_VERSION);

    private LoggerContext context;
    private boolean schemaReadyBefore;
    private final SentinelServicePlugin plugin = new SentinelServicePlugin();

    @BeforeEach
    void setup() throws Exception {
        LogPipeline.get().stop();
        context = LogEventAppenderTest.engineLikeContext();
        schemaReadyBefore = SentinelServicePlugin.isSchemaReady();
    }

    @AfterEach
    void cleanup() throws Exception {
        LogPipeline.get().stop();
        context.stop();
        Field schemaReady = SentinelServicePlugin.class.getDeclaredField("schemaReady");
        schemaReady.setAccessible(true);
        schemaReady.set(null, schemaReadyBefore);
    }

    private long appenders() {
        return LogEventAppenderTest.appendersNamedOurs(context);
    }

    private static boolean matcherAlive() {
        return LogPipeline.get().matcher().isAlive();
    }

    @Test
    void thePipelineWaitsForTheSchemaGateAndStartsStopsAndRestartsWithThePlugin() {
        withEngineReplaced(null, engine -> {
            plugin.start();
            assertEquals(0, appenders());
            assertFalse(matcherAlive());
            plugin.stop();
        });
        withEngineReplaced(READY, engine -> {
            plugin.start();
            assertEquals(1, appenders());
            assertTrue(matcherAlive());
            plugin.start();
            assertEquals(1, appenders(), "a repeated start adds nothing");
            plugin.stop();
            assertEquals(0, appenders());
            assertFalse(matcherAlive());
            plugin.start();
            assertEquals(1, appenders());
            assertTrue(matcherAlive());
            plugin.stop();
            plugin.stop();
            assertEquals(0, appenders());
        });
    }

    @Test
    void aLinkageErrorAtAttachOrDetachDoesNotStopThePlugin() {
        withEngineReplaced(READY, engine -> {
            engine.appender.when(() -> LogEventAppender.attachToEngine(any()))
                    .thenThrow(new NoClassDefFoundError("org/apache/logging/log4j/core/Appender"));
            engine.appender.when(LogEventAppender::detach).thenThrow(new LinkageError("detach"));
            assertDoesNotThrow(plugin::start);
            verify(engine.scheduler).start(any());
            assertDoesNotThrow(plugin::stop);
            engine.leadership.verify(SentinelLeadership::stopHeartbeat);
            assertFalse(matcherAlive(), "the matcher thread stops all the same");
        });
    }

    private record Engine(MockedStatic<LogEventAppender> appender, MockedStatic<SentinelLeadership> leadership,
            SentinelScheduler scheduler) {
    }

    private interface Body {
        void run(Engine engine);
    }

    private void withEngineReplaced(String schemaVersion, Body body) {
        ConfigurationController configuration = mock(ConfigurationController.class);
        when(configuration.getProperty(SentinelMigrator.PLUGIN_NAME, SentinelMigrator.VERSION_PROPERTY))
                .thenReturn(schemaVersion);
        ControllerFactory factory = mock(ControllerFactory.class);
        when(factory.createEventController()).thenReturn(mock(EventController.class));
        SentinelScheduler scheduler = mock(SentinelScheduler.class);
        try (MockedStatic<LogEventAppender> appender = mockStatic(LogEventAppender.class, CALLS_REAL_METHODS);
                MockedStatic<ConfigurationController> configurations = mockStatic(ConfigurationController.class);
                MockedStatic<ControllerFactory> controllers = mockStatic(ControllerFactory.class);
                MockedStatic<ActionDispatcher> dispatcher = mockStatic(ActionDispatcher.class);
                MockedStatic<SentinelLeadership> leadership = mockStatic(SentinelLeadership.class);
                MockedStatic<SentinelScheduler> schedulers = mockStatic(SentinelScheduler.class);
                MockedConstruction<SentinelConnectorStatusListener> listeners =
                        mockConstruction(SentinelConnectorStatusListener.class)) {
            appender.when(LogEventAppender::engineContext).thenReturn(context);
            configurations.when(ConfigurationController::getInstance).thenReturn(configuration);
            controllers.when(ControllerFactory::getFactory).thenReturn(factory);
            schedulers.when(SentinelScheduler::getInstance).thenReturn(scheduler);
            body.run(new Engine(appender, leadership, scheduler));
        }
    }
}
