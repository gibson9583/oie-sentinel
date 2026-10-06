package org.openintegrationengine.plugins.sentinel.shared;

import static org.junit.jupiter.api.Assertions.*;
import javax.ws.rs.POST;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.client.core.api.Param;
import org.junit.jupiter.api.Test;

class ImportReviewContractTest {
    @Test void reviewAndApplyRequireManageAndNeverAuditCredentialBodies() throws Exception {
        for (String operation : new String[]{"previewImport","applyReviewedImport"}) {
            var method=SentinelServletInterface.class.getMethod(operation,String.class);
            assertNotNull(method.getAnnotation(POST.class));
            assertEquals(SentinelServletInterface.PERMISSION_MANAGE,method.getAnnotation(MirthOperation.class).permission());
            assertTrue(method.getParameters()[0].getAnnotation(Param.class).excludeFromAudit());
        }
        assertFalse(SentinelServletInterface.class.getMethod("previewImport",String.class).getAnnotation(MirthOperation.class).auditable());
    }
}
