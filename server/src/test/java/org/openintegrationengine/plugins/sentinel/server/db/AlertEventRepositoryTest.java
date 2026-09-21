/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class AlertEventRepositoryTest {

    @Test
    void dispatchTimeSuppressionStateUpdateExistsOnEveryVendor() throws Exception {
        Path cwd = Path.of("").toAbsolutePath();
        Path root = Files.isDirectory(cwd.resolve("package/resources/mapper")) ? cwd : cwd.getParent();
        for (String vendor : List.of("derby", "mysql", "oracle", "postgres", "sqlserver")) {
            String mapper = Files.readString(
                    root.resolve("package/resources/mapper/" + vendor + "-sqlmap.xml"));
            int start = mapper.indexOf("<update id=\"setAlertEventSuppressed\"");
            int end = mapper.indexOf("</update>", start);
            assertTrue(start >= 0 && end > start, vendor);
            String statement = mapper.substring(start, end);
            assertTrue(statement.contains("SET suppressed = #{suppressed}"), vendor);
            assertTrue(statement.contains("WHERE id = #{id}"), vendor);

            assertTrue(mapper.contains("<update id=\"setResolutionPending\""), vendor);
            assertTrue(mapper.contains("<select id=\"listPendingResolvedAlertEvents\""), vendor);
            assertTrue(mapper.contains("resolution_pending = #{resolution_pending}"), vendor);
            assertTrue(mapper.contains("resolution_pending"), vendor);
            assertTrue(mapper.contains("<update id=\"setProblemPending\""), vendor);
            assertTrue(mapper.contains("<select id=\"listPendingProblemAlertEvents\""), vendor);
            assertTrue(mapper.contains("#{problem_pending}"), vendor);
            assertTrue(mapper.contains("problem_pending"), vendor);

            int manualStart = mapper.indexOf("<update id=\"resolveAlertEventManually\"");
            int manualEnd = mapper.indexOf("</update>", manualStart);
            assertTrue(manualStart >= 0 && manualEnd > manualStart, vendor);
            assertTrue(mapper.substring(manualStart, manualEnd)
                    .contains("resolution_pending = #{resolution_pending}"), vendor);
        }
    }
}
