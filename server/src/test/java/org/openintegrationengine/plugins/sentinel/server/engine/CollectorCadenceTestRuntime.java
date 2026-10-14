/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.mockito.MockedConstruction;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.openintegrationengine.plugins.sentinel.shared.model.SentinelSettings;

/** Real lifecycle/trigger construction, with an inert Quartz executor and controlled installation time. */
final class CollectorCadenceTestRuntime implements AutoCloseable {
    final SentinelScheduler runtime;
    final Scheduler quartz = mock(Scheduler.class);
    final Map<TriggerKey, Trigger> triggers = new HashMap<>();
    private final MockedConstruction<StdSchedulerFactory> factories;

    CollectorCadenceTestRuntime() throws Exception {
        var constructor = SentinelScheduler.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        runtime = constructor.newInstance();
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

    void start(int interval, Instant at) { at(at, () -> runtime.start(settings(interval))); }
    void reschedule(int interval, Instant at) { at(at, () -> runtime.reschedule(settings(interval))); }

    static void at(Instant instant, Runnable action) {
        try (var clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(instant);
            action.run();
        }
    }

    static SentinelSettings settings(int interval) {
        SentinelSettings settings = new SentinelSettings();
        settings.setCollectorIntervalSeconds(interval);
        return settings;
    }

    @Override public void close() {
        try { runtime.shutdown(); }
        finally { factories.close(); }
    }
}
