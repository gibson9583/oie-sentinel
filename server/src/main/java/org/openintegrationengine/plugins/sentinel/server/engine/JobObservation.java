/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.engine;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openintegrationengine.plugins.sentinel.server.db.*;

/** Best-effort telemetry; its failure must never prevent collection or evaluation. */
final class JobObservation implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(JobObservation.class);
    private static final ThreadLocal<JobObservation> current=new ThreadLocal<>();
    private final String job,runId;
    private final LeaseFence fence;
    private boolean started,failed;
    private JobObservation(String job,LeaseFence fence) { this.job=job;this.fence=fence;this.runId=UUID.randomUUID().toString(); }
    static JobObservation begin(String job,LeaseFence fence) {
        var observation=new JobObservation(job,fence);current.set(observation);
        if(fence!=null&&fence.isManaged()) {
            try { JobObservationRepository.start(job,observation.runId,fence);observation.started=true; }
            catch(Exception error) { log.warn("Shared {} start observation unavailable",job,error); }
        }
        return observation;
    }
    static void failedUnit() { var observation=current.get();if(observation!=null)observation.failed=true; }
    @Override public void close() {
        current.remove();
        if(started) {
            try { JobObservationRepository.finish(job,runId,fence,!failed); }
            catch(Exception error) { log.warn("Shared {} completion observation unavailable; state remains unknown",job,error); }
        }
    }
}
