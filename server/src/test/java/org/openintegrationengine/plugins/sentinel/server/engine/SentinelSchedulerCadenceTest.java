/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import com.mirth.connect.server.controllers.ConfigurationController;
import org.openintegrationengine.plugins.sentinel.server.service.SettingsService;
import org.openintegrationengine.plugins.sentinel.shared.model.SentinelSettings;

/** Production lifecycle and real built triggers; the Quartz executor is inert. */
class SentinelSchedulerCadenceTest {
    private static final TriggerKey COLLECTOR = TriggerKey.triggerKey("activityCollectorTrigger", "sentinel");
    private static final TriggerKey EVALUATOR = TriggerKey.triggerKey("triggerEvaluatorTrigger", "sentinel");
    private SentinelScheduler runtime;
    private Scheduler quartz;
    private MockedConstruction<StdSchedulerFactory> factories;
    private final Map<TriggerKey, Trigger> triggers = new HashMap<>();

    @BeforeEach
    void setup() throws Exception {
        var constructor = SentinelScheduler.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        runtime = constructor.newInstance();
        quartz = mock(Scheduler.class);
        factories = mockConstruction(StdSchedulerFactory.class,
                (factory, context) -> when(factory.getScheduler()).thenReturn(quartz));
        when(quartz.scheduleJob(any(JobDetail.class), any(Trigger.class))).thenAnswer(call -> {
            Trigger trigger = call.getArgument(1);
            triggers.put(trigger.getKey(), trigger);
            return new Date();
        });
        when(quartz.getTrigger(any())).thenAnswer(call -> triggers.get(call.getArgument(0)));
        when(quartz.getJobDetail(any())).thenAnswer(call ->
                org.quartz.JobBuilder.newJob(ActivityCollectorJob.class)
                        .withIdentity(call.getArgument(0, org.quartz.JobKey.class)).build());
        when(quartz.rescheduleJob(any(), any())).thenAnswer(call -> {
            triggers.put(call.getArgument(0), call.getArgument(1));
            return new Date();
        });
    }

    @AfterEach
    void cleanup() {
        runtime.shutdown();
        factories.close();
    }

    @Test
    void startDefaultInstallStopRestartAndDuplicateStartUseTheActualTrigger() {
        assertEquals(0, runtime.getCollectorIntervalSeconds());
        runtime.reschedule(settings(10));
        assertEquals(0, runtime.getCollectorIntervalSeconds());
        try (var controllers = mockStatic(ConfigurationController.class)) {
            controllers.when(ConfigurationController::getInstance).thenReturn(mock(ConfigurationController.class));
            runtime.start(SettingsService.get());
        }
        assertEquals(30, runtime.getCollectorIntervalSeconds());
        runtime.start(settings(10));
        assertEquals(30, runtime.getCollectorIntervalSeconds());
        runtime.shutdown();
        assertEquals(0, runtime.getCollectorIntervalSeconds());
        SentinelSettings requested = settings(10);
        runtime.start(requested);
        requested.setCollectorIntervalSeconds(600);
        assertEquals(10, runtime.getCollectorIntervalSeconds());
        runtime.reschedule(settings(600));
        assertEquals(600, runtime.getCollectorIntervalSeconds());
        runtime.reschedule(settings(30));
        assertEquals(30, runtime.getCollectorIntervalSeconds());
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
        assertEquals(scenario.equals("evaluator-fails") ? 10 : 30, runtime.getCollectorIntervalSeconds());
    }

    @Test
    void failedStartCannotSupplyAnAuthoritativeCadence() throws Exception {
        doThrow(new SchedulerException("start failure")).when(quartz).start();
        assertThrows(RuntimeException.class, () -> runtime.start(settings(10)));
        assertEquals(0, runtime.getCollectorIntervalSeconds());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrong-type", "throw", "zero", "negative", "fractional", "overflow"})
    void unreadableOrUnexpectedTriggerDefersCoverage(String scenario) throws Exception {
        runtime.start(settings(30));
        switch (scenario) {
            case "missing" -> triggers.remove(COLLECTOR);
            case "wrong-type" -> triggers.put(COLLECTOR, mock(Trigger.class));
            case "throw" -> when(quartz.getTrigger(COLLECTOR)).thenThrow(new SchedulerException("read failure"));
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
        assertEquals(0, runtime.getCollectorIntervalSeconds());
    }

    @Test
    void cadenceReadDoesNotWaitOnShutdownThatIsWaitingForAnEvaluator() throws Exception {
        runtime.start(settings(10));
        AtomicInteger read = new AtomicInteger();
        doAnswer(call -> {
            Thread evaluator = new Thread(() -> read.set(runtime.getCollectorIntervalSeconds()), "cadence-shutdown-reader");
            evaluator.setDaemon(true);
            evaluator.start();
            evaluator.join(1000);
            assertFalse(evaluator.isAlive(), "Shutdown must not deadlock a running evaluator");
            return null;
        }).when(quartz).shutdown(true);
        runtime.shutdown();
        assertEquals(10, read.get());
        assertEquals(0, runtime.getCollectorIntervalSeconds());
    }

    private static SentinelSettings settings(int interval) {
        SentinelSettings settings = new SentinelSettings();
        settings.setCollectorIntervalSeconds(interval);
        return settings;
    }
}
