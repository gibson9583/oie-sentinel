/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.JobDetail;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerKey;

import org.openintegrationengine.plugins.sentinel.server.log.LogMatchFlushJob;

/** The log match flush is scheduled at the collector interval and follows it when an operator changes it. */
class LogMatchFlushScheduleTest {
    private static final TriggerKey FLUSH = TriggerKey.triggerKey("logMatchFlushTrigger", "sentinel");
    private static final TriggerKey COLLECTOR = TriggerKey.triggerKey("activityCollectorTrigger", "sentinel");

    private CollectorCadenceTestRuntime fixture;

    @BeforeEach
    void setup() throws Exception {
        fixture = new CollectorCadenceTestRuntime();
    }

    @AfterEach
    void cleanup() {
        fixture.close();
    }

    private long flushIntervalSeconds() {
        return ((SimpleTrigger) fixture.triggers.get(FLUSH)).getRepeatInterval() / 1000;
    }

    @Test
    void theFlushIsScheduledAtTheCollectorIntervalAsItsOwnJob() throws Exception {
        fixture.start(45, Instant.parse("2026-11-21T08:00:00Z"));
        assertEquals(45, flushIntervalSeconds());
        ArgumentCaptor<JobDetail> jobs = ArgumentCaptor.forClass(JobDetail.class);
        verify(fixture.quartz, times(5)).scheduleJob(jobs.capture(), any(Trigger.class));
        JobDetail flush = jobs.getAllValues().stream().filter(job -> job.getJobClass() == LogMatchFlushJob.class)
                .findFirst().orElseThrow();
        assertEquals(1, jobs.getAllValues().stream().filter(job -> job.getJobClass() == LogMatchFlushJob.class).count());
        assertEquals(flush.getKey(), fixture.triggers.get(FLUSH).getJobKey());
    }

    @Test
    void aCollectorIntervalChangeMovesTheFlushAndAnUnchangedOneLeavesItAlone() throws Exception {
        Instant start = Instant.parse("2026-11-21T08:00:00Z");
        fixture.start(30, start);
        Trigger original = fixture.triggers.get(FLUSH);
        fixture.reschedule(30, start.plusSeconds(60));
        assertSame(original, fixture.triggers.get(FLUSH));
        verify(fixture.quartz, never()).rescheduleJob(eq(FLUSH), any());

        fixture.reschedule(600, start.plusSeconds(120));
        assertEquals(600, flushIntervalSeconds());
        assertEquals(600, ((SimpleTrigger) fixture.triggers.get(COLLECTOR)).getRepeatInterval() / 1000);
    }
}
