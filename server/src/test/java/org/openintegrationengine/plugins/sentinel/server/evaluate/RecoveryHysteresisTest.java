package org.openintegrationengine.plugins.sentinel.server.evaluate;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;

class RecoveryHysteresisTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void rejectsNonIntegralAndOutOfBoundsConfiguration() throws Exception {
        for (String raw : new String[]{"0", "-1", "1.5", "true", "\"3\"", "10001", "2147483648"}) {
            assertThrows(IllegalArgumentException.class, () -> RecoveryHysteresis.validate(json.readTree("{\"minConsecutiveRecoveries\":"+raw+"}")), raw);
        }
        for (String raw : new String[]{"{}", "{\"minConsecutiveRecoveries\":null}", "{\"minConsecutiveRecoveries\":1}", "{\"minConsecutiveRecoveries\":10000}"})
            assertDoesNotThrow(() -> RecoveryHysteresis.validate(json.readTree(raw)));
    }
    @Test void progressIsIncidentAndPolicyBoundAndUnknownResets() {
        Monitor m = new Monitor(); m.setConfigJson("{\"minConsecutiveRecoveries\":3}");
        TriggerState state = new TriggerState(); state.setOpenAlertEventId(7L);
        state.setLastValueJson("{\"sentinelRecovery\":{\"alertEventId\":6,\"healthyCount\":2,\"required\":3}}");
        assertEquals(1, RecoveryHysteresis.advance(m,state,EvaluationOutcome.ok("{}")).healthyCount());
        state.setLastValueJson("{\"sentinelRecovery\":{\"alertEventId\":7,\"healthyCount\":2,\"required\":2}}");
        assertEquals(1, RecoveryHysteresis.advance(m,state,EvaluationOutcome.ok("{}")).healthyCount());
        state.setLastValueJson("{\"sentinelRecovery\":{\"alertEventId\":7,\"healthyCount\":2,\"required\":3}}");
        assertEquals(0, RecoveryHysteresis.advance(m,state,EvaluationOutcome.insufficientData("{}")).healthyCount());
        assertEquals(0, RecoveryHysteresis.advance(m,state,EvaluationOutcome.breach("{}","bad")).healthyCount());
        assertEquals(3, RecoveryHysteresis.advance(m,state,EvaluationOutcome.ok("{}")).healthyCount());
    }
    @Test void evidencePreservesTypeSpecificFieldsAndToleratesMissingLegacyProgress() throws Exception {
        Monitor m = new Monitor(); m.setConfigJson("{\"minConsecutiveRecoveries\":2}");
        TriggerState s = new TriggerState(); s.setOpenAlertEventId(9L); s.setLastValueJson("invalid");
        var progress=RecoveryHysteresis.advance(m,s,EvaluationOutcome.ok("{}"));
        var value=json.readTree(RecoveryHysteresis.evidence("{\"stateSinceIso\":\"2026-01-01T00:00:00Z\",\"errorRatePercent\":4}",progress));
        assertEquals("2026-01-01T00:00:00Z",value.path("stateSinceIso").asText());
        assertEquals(4,value.path("errorRatePercent").asInt());
        assertEquals(1,value.path("sentinelRecovery").path("healthyCount").asInt());
        s.setOpenAlertEventId(null); assertEquals(0,RecoveryHysteresis.advance(m,s,EvaluationOutcome.ok("{}")).healthyCount());
    }
}
