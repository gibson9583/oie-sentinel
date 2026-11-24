/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.properties.PropertiesConfigurationFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mirth.connect.server.logging.MirthLog4jFilter;

/**
 * The appender on a real log4j2 context configured like the engine: the
 * rootLogger line and logger levels are copied from the
 * {@code conf/log4j2.properties} OIE 4.6.0 and 4.7.0 ship, with Null
 * appenders so nothing is written.
 */
class LogEventAppenderTest {

    static final String ENGINE_PROPERTIES = String.join("\n",
            "rootLogger = ERROR,stdout,fout",
            "appender.console.type = Null",
            "appender.console.name = stdout",
            "appender.rolling.type = Null",
            "appender.rolling.name = fout",
            "logger.mirth.name = com.mirth.connect.server.Mirth",
            "logger.mirth.level = INFO",
            "logger.transformer.name = transformer",
            "logger.transformer.level = DEBUG",
            "logger.db-connector.name = db-connector",
            "logger.db-connector.level = DEBUG",
            "");

    private static final AtomicInteger CONTEXTS = new AtomicInteger();

    private LoggerContext context;

    static LoggerContext engineLikeContext() throws Exception {
        LoggerContext context = new LoggerContext("sentinel-log-test-" + CONTEXTS.incrementAndGet());
        context.start(engineConfiguration(context));
        return context;
    }

    static Configuration engineConfiguration(LoggerContext context) throws Exception {
        ConfigurationSource source = new ConfigurationSource(
                new ByteArrayInputStream(ENGINE_PROPERTIES.getBytes(StandardCharsets.UTF_8)));
        return new PropertiesConfigurationFactory().getConfiguration(context, source);
    }

    static long appendersNamedOurs(LoggerContext context) {
        return context.getConfiguration().getRootLogger().getAppenders().keySet().stream()
                .filter(LogEventAppender.NAME::equals).count();
    }

    @BeforeEach
    void setup() throws Exception {
        LogEventAppender.detach();
        context = engineLikeContext();
    }

    @AfterEach
    void cleanup() {
        LogEventAppender.detach();
        context.stop();
    }

    @Test
    void appendQueuesACopyWithoutMatchingAndNeverThrows() {
        LogPipeline pipeline = new LogPipeline(new MirthLog4jFilter(), System::nanoTime);
        pipeline.refresh(List.of(LogMatchCounterTest.monitor(1, "{\"pattern\": \"((x+)+)+y\"}")));
        LogEventAppender.attach(context, pipeline.queue());
        Logger logger = context.getLogger("db");

        logger.error("Query on {} failed", "ADT in", new SQLException("ORA-00942: table or view does not exist"));
        logger.error("m".repeat(10_000));
        LogQueue.Line copied = pipeline.queue().poll();
        assertEquals(Level.ERROR, copied.level());
        assertEquals("db", copied.logger());
        assertEquals("Query on ADT in failed\njava.sql.SQLException: ORA-00942: table or view does not exist",
                copied.text());
        assertEquals(LogQueue.MAX_TEXT_CHARS, pipeline.queue().poll().message().length());

        // Matching on this thread would take at least 30 x the 20 ms limit.
        long started = System.nanoTime();
        for (int i = 0; i < 30; i++) {
            logger.error("x".repeat(40));
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsedMillis < 300, "30 lines took " + elapsedMillis + " ms on the logging thread");
        assertEquals(30, pipeline.queue().depth());
        assertEquals(Map.of(), pipeline.counter().drainTimedOut(), "nothing matched yet");
        assertEquals(30, pipeline.matcher().processQueued());
        assertEquals(Map.of(1, 30L), pipeline.counter().drainTimedOut(), "the matcher thread ran the pattern");

        LogEvent hostile = mock(LogEvent.class);
        when(hostile.getLevel()).thenThrow(new IllegalStateException("broken event"));
        LogEventAppender appender = (LogEventAppender) context.getConfiguration().getAppender(LogEventAppender.NAME);
        assertDoesNotThrow(() -> appender.append(hostile));
        assertEquals(1, pipeline.queue().drainInternalErrors());
    }

    @Test
    void attachIsIdempotentSeesChildLoggersAndComesBackAfterAReconfiguration() throws Exception {
        LogQueue queue = new LogQueue(100);
        queue.setGate(List.of(LogQueueTest.matcher("{\"minLevel\": \"TRACE\"}")));
        assertTrue(LogEventAppender.attach(context, queue));
        assertFalse(LogEventAppender.attach(context, queue));
        assertEquals(1, appendersNamedOurs(context));

        context.getLogger("transformer").debug("passes the transformer logger's DEBUG though the root is ERROR");
        context.getLogger("transformer").trace("below the transformer logger's level");
        context.getLogger("com.example.Engine").info("no logger of its own, so the root's ERROR applies");
        assertEquals(1, queue.depth());

        context.setConfiguration(engineConfiguration(context));
        assertFalse(LogEventAppender.isAttached(), "log4j drops appenders added in code");
        assertTrue(LogEventAppender.ensureAttached());
        assertFalse(LogEventAppender.ensureAttached());
        assertEquals(1, appendersNamedOurs(context));

        LogEventAppender.detach();
        LogEventAppender.detach();
        assertEquals(0, appendersNamedOurs(context));
        assertNull(context.getConfiguration().getAppender(LogEventAppender.NAME));
        assertFalse(LogEventAppender.ensureAttached(), "nothing to put back once detached");
    }
}
