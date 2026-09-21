/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class ConnectorStatusMapperContractTest {

    private static final List<String> VENDORS =
            List.of("derby", "mysql", "oracle", "postgres", "sqlserver");

    @Test
    void everyConnectorResultMapAndSelectPreservesLowercaseNodeIdentity() throws Exception {
        for (String vendor : VENDORS) {
            String mapper = mapper(vendor);
            if (vendor.equals("derby") || vendor.equals("oracle")) {
                String connectorMap = between(mapper,
                        "<resultMap id=\"connectorStatusEventResult\"", "</resultMap>");
                assertTrue(connectorMap.contains("property=\"node_id\" column=\"node_id\""), vendor);
                String alertMap = between(mapper, "<resultMap id=\"alertEventResult\"", "</resultMap>");
                assertFalse(alertMap.contains("property=\"node_id\""), vendor);
            }
            String latest = between(mapper,
                    "<select id=\"listLatestConnectorStatusEvents\"", "</select>");
            assertTrue(latest.contains("e.node_id"), vendor);
        }
    }

    @Test
    void everyLatestAndPruneQueryUsesGeneratedArrivalOrderNotWallClockOrder() throws Exception {
        for (String vendor : VENDORS) {
            String mapper = mapper(vendor);
            String latest = between(mapper,
                    "<select id=\"listLatestConnectorStatusEvents\"", "</select>");
            assertTrue(latest.contains("newer.id &gt; e.id"), vendor);
            assertFalse(latest.contains("newer.changed_time &gt;"), vendor);

            String prune = between(mapper,
                    "<delete id=\"deleteConnectorStatusEventsOlderThan\"", "</delete>");
            assertTrue(prune.contains("newer.id &gt; old.id"), vendor);
            assertFalse(prune.contains("newer.changed_time &gt;"), vendor);
        }
    }

    private static String mapper(String vendor) throws Exception {
        Path cwd = Path.of("").toAbsolutePath();
        Path root = Files.isDirectory(cwd.resolve("package/resources/mapper")) ? cwd : cwd.getParent();
        return Files.readString(root.resolve("package/resources/mapper/" + vendor + "-sqlmap.xml"));
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        int to = text.indexOf(end, from);
        if (from < 0 || to < 0) {
            throw new AssertionError("missing mapper section " + start);
        }
        return text.substring(from, to + end.length());
    }
}
