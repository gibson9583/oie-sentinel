package org.openintegrationengine.plugins.sentinel.shared;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.mirth.connect.client.core.api.MirthOperation;
import javax.ws.rs.GET;
class DeliveryContractTest {
    @Test void readsUseViewPermissionAndNeverCreateAuditedSideEffects() {
        for (var method : SentinelServletInterface.class.getMethods()) {
            if (!method.getName().equals("getDeliveries") && !method.getName().equals("getPendingDeliveries") && !method.getName().equals("getClusterHealth")) continue;
            assertNotNull(method.getAnnotation(GET.class));
            var operation=method.getAnnotation(MirthOperation.class);
            assertEquals(SentinelServletInterface.PERMISSION_VIEW,operation.permission());
            assertFalse(operation.auditable());
        }
    }
}
