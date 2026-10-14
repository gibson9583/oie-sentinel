/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import com.mirth.connect.server.controllers.ConfigurationController;
import org.openintegrationengine.plugins.sentinel.server.service.SettingsService;
import org.openintegrationengine.plugins.sentinel.shared.model.SentinelSettings;

/** Production lifecycle and real built triggers; the Quartz executor is inert. */
class SentinelSchedulerCadenceTest {
    private static final TriggerKey COLLECTOR = TriggerKey.triggerKey("activityCollectorTrigger", "sentinel");
    private static final TriggerKey EVALUATOR = TriggerKey.triggerKey("triggerEvaluatorTrigger", "sentinel");
    private SentinelScheduler runtime;
    private Scheduler quartz;
    private CollectorCadenceTestRuntime fixture;
    private Map<TriggerKey, Trigger> triggers;

    @BeforeEach
    void setup() throws Exception {
        fixture = new CollectorCadenceTestRuntime();
        runtime = fixture.runtime;
        quartz = fixture.quartz;
        triggers = fixture.triggers;
    }

    @AfterEach
    void cleanup() { fixture.close(); }

    @Test
    void startDefaultInstallStopRestartAndDuplicateStartUseTheActualTrigger() {
        assertEquals(0, runtime.getCollectorIntervalSeconds(Instant.now()));
        runtime.reschedule(settings(10));
        assertEquals(0, runtime.getCollectorIntervalSeconds(Instant.now()));
        try (var controllers = mockStatic(ConfigurationController.class)) {
            controllers.when(ConfigurationController::getInstance).thenReturn(mock(ConfigurationController.class));
            runtime.start(SettingsService.get());
        }
        assertEquals(30, runtime.getCollectorIntervalSeconds(Instant.now()));
        runtime.start(settings(10));
        assertEquals(30, runtime.getCollectorIntervalSeconds(Instant.now()));
        runtime.shutdown();
        assertEquals(0, runtime.getCollectorIntervalSeconds(Instant.now()));
        SentinelSettings requested = settings(10);
        runtime.start(requested);
        requested.setCollectorIntervalSeconds(600);
        assertEquals(10, runtime.getCollectorIntervalSeconds(Instant.now()));
        runtime.reschedule(settings(600));
        assertEquals(600, runtime.getCollectorIntervalSeconds(Instant.now()));
        runtime.reschedule(settings(30));
        assertEquals(30, runtime.getCollectorIntervalSeconds(Instant.now()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"collector-fails", "evaluator-fails", "collector-not-replaced"})
    void failedOrPartialRescheduleReportsWhatQuartzActuallyApplied(String scenario) throws Exception {
        runtime.start(settings(30));
        if (scenario.equals("collector-fails")) {
            when(quartz.rescheduleJob(eq(COLLECTOR), any())).thenThrow(new SchedulerException("collector failure"));
        } else if (scenario.equals("evaluator-fails")) {
            when(quartz.rescheduleJob(eq(EVALUATOR), any())).thenThrow(new SchedulerException("evaluator failure"));
        } else {
            when(quartz.rescheduleJob(eq(COLLECTOR), any())).thenReturn(null);
        }
        if (scenario.equals("collector-not-replaced")) runtime.reschedule(settings(10));
        else assertThrows(RuntimeException.class, () -> runtime.reschedule(settings(10)));
        assertEquals(scenario.equals("evaluator-fails") ? 10 : 30, runtime.getCollectorIntervalSeconds(Instant.now()));
    }

    @Test
    void failedStartCannotSupplyAnAuthoritativeCadence() throws Exception {
        doThrow(new SchedulerException("start failure")).when(quartz).start();
        assertThrows(RuntimeException.class, () -> runtime.start(settings(10)));
        assertEquals(0, runtime.getCollectorIntervalSeconds(Instant.now()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrong-type", "throw", "runtime-throw", "zero", "negative", "fractional", "overflow"})
    void unreadableOrUnexpectedTriggerDefersCoverage(String scenario) throws Exception {
        runtime.start(settings(30));
        switch (scenario) {
            case "missing" -> triggers.remove(COLLECTOR);
            case "wrong-type" -> triggers.put(COLLECTOR, mock(Trigger.class));
            case "throw" -> when(quartz.getTrigger(COLLECTOR)).thenThrow(new SchedulerException("read failure"));
            case "runtime-throw" -> when(quartz.getTrigger(COLLECTOR)).thenThrow(new IllegalStateException("runtime read failure"));
            default -> {
                SimpleTrigger trigger = mock(SimpleTrigger.class);
                when(trigger.getRepeatInterval()).thenReturn(switch (scenario) {
                    case "negative" -> -1000L;
                    case "fractional" -> 1500L;
                    case "overflow" -> Long.MAX_VALUE;
                    default -> 0L;
                });
                triggers.put(COLLECTOR, trigger);
            }
        }
        assertEquals(0, runtime.getCollectorIntervalSeconds(Instant.now()));
    }

    @Test
    void cadenceReadDoesNotWaitOnShutdownThatIsWaitingForAnEvaluator() throws Exception {
        runtime.start(settings(10));
        AtomicInteger read = new AtomicInteger();
        doAnswer(call -> {
            Thread evaluator = new Thread(() -> read.set(runtime.getCollectorIntervalSeconds(Instant.now())), "cadence-shutdown-reader");
            evaluator.setDaemon(true);
            evaluator.start();
            evaluator.join(1000);
            assertFalse(evaluator.isAlive(), "Shutdown must not deadlock a running evaluator");
            return null;
        }).when(quartz).shutdown(true);
        runtime.shutdown();
        assertEquals(10, read.get());
        assertEquals(0, runtime.getCollectorIntervalSeconds(Instant.now()));
    }

    @ParameterizedTest
    @ValueSource(ints = {30, 600})
    void scheduleChangeAndRestartOnlyCertifyNewObservations(int original) {
        Instant first = Instant.parse("2026-10-14T08:00:00Z");
        Instant change = first.plusSeconds(7200);
        fixture.start(original, first);
        assertEquals(0, runtime.getCollectorIntervalSeconds(first.minusNanos(1)));
        assertEquals(original, runtime.getCollectorIntervalSeconds(first));
        fixture.reschedule(original == 30 ? 600 : 30, change);
        assertEquals(0, runtime.getCollectorIntervalSeconds(change.minusNanos(1)));
        assertEquals(original == 30 ? 600 : 30, runtime.getCollectorIntervalSeconds(change));
        runtime.shutdown();
        fixture.start(600, change.plusSeconds(7200));
        assertEquals(0, runtime.getCollectorIntervalSeconds(change));
        assertEquals(600, runtime.getCollectorIntervalSeconds(change.plusSeconds(7200)));
    }

    @Test
    void unchangedCadencePreservesTriggerAndBoundaryIncludingEvaluatorFailure() throws Exception {
        Instant start = Instant.parse("2026-10-14T08:00:00Z");
        fixture.start(600, start);
        Trigger original = triggers.get(COLLECTOR);
        fixture.reschedule(600, start.plusSeconds(7200));
        assertSame(original, triggers.get(COLLECTOR));
        assertEquals(600, runtime.getCollectorIntervalSeconds(start));
        when(quartz.rescheduleJob(eq(EVALUATOR), any())).thenThrow(new SchedulerException("evaluator failure"));
        assertThrows(RuntimeException.class, () -> fixture.reschedule(600, start.plusSeconds(14400)));
        assertSame(original, triggers.get(COLLECTOR));
        assertEquals(600, runtime.getCollectorIntervalSeconds(start));
        verify(quartz, never()).rescheduleJob(eq(COLLECTOR), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "throws-after-install", "evaluator-fails", "not-replaced"})
    void installationAndItsDelayCannotCertifyEarlierHistory(String scenario) throws Exception {
        Instant start = Instant.parse("2026-10-14T08:00:00Z");
        Instant requested = start.plusSeconds(7200);
        Instant installed = requested.plusSeconds(120);
        fixture.start(30, start);
        when(quartz.rescheduleJob(eq(COLLECTOR), any())).thenAnswer(call -> {
            assertEquals(0, runtime.getCollectorIntervalSeconds(installed.plusSeconds(3600)), "unknown during installation");
            if (!scenario.equals("not-replaced")) triggers.put(COLLECTOR, call.getArgument(1));
            if (scenario.equals("throws-after-install")) throw new SchedulerException("response lost after installation");
            return scenario.equals("not-replaced") ? null : new Date();
        });
        if (scenario.equals("evaluator-fails")) {
            when(quartz.rescheduleJob(eq(EVALUATOR), any())).thenThrow(new SchedulerException("evaluator failure"));
        }
        // First now() is after Quartz returns, not when the replacement was built.
        if (scenario.contains("fails") || scenario.contains("throws")) {
            assertThrows(RuntimeException.class, () -> fixture.reschedule(600, installed));
        } else fixture.reschedule(600, installed);
        assertEquals(0, runtime.getCollectorIntervalSeconds(requested));
        assertEquals(0, runtime.getCollectorIntervalSeconds(installed.minusNanos(1)));
        assertEquals(scenario.equals("not-replaced") ? 30 : 600, runtime.getCollectorIntervalSeconds(installed));
    }

    @Test
    void readCrossingConcurrentCadenceChangeCannotUseOldBoundaryWithNewInterval() throws Exception {
        Instant start = Instant.parse("2026-10-14T08:00:00Z");
        fixture.start(30, start);
        when(quartz.getTrigger(COLLECTOR)).thenAnswer(call -> {
            // Simulate the replacement racing between boundary and trigger reads.
            doAnswer(next -> triggers.get(COLLECTOR)).when(quartz).getTrigger(COLLECTOR);
            fixture.reschedule(600, start.plusSeconds(7200));
            return triggers.get(COLLECTOR);
        });
        assertEquals(0, runtime.getCollectorIntervalSeconds(start.plusSeconds(3600)));
    }

    @Test
    void evaluatorCannotCertifyCoverageBeforeSchedulerStartupCompletes() throws Exception {
        Instant start = Instant.parse("2026-10-14T08:00:00Z");
        doAnswer(call -> {
            assertEquals(0, runtime.getCollectorIntervalSeconds(start.plusSeconds(3600)));
            return null;
        }).when(quartz).start();
        fixture.start(600, start);
        assertEquals(600, runtime.getCollectorIntervalSeconds(start));
        assertEquals(0, runtime.getCollectorIntervalSeconds(null));
    }

    private static SentinelSettings settings(int interval) {
        SentinelSettings settings = new SentinelSettings();
        settings.setCollectorIntervalSeconds(interval);
        return settings;
    }
}
