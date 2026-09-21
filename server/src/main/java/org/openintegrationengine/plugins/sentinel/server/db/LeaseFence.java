/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import java.util.Map;

/** Immutable fencing token captured by a background job at tick start. */
public record LeaseFence(String leaseName, String nodeId, Long epoch) {

    private static final LeaseFence UNMANAGED = new LeaseFence(null, null, null);

    /** Used before leadership is engaged, principally by isolated unit tests. */
    public static LeaseFence unmanaged() {
        return UNMANAGED;
    }

    public boolean isManaged() {
        return epoch != null;
    }

    /** Adds the mapper parameters shared by every fenced transaction. */
    public void bind(Map<String, Object> params) {
        if (isManaged()) {
            params.put("leaseName", leaseName);
            params.put("nodeId", nodeId);
            params.put("leaseEpoch", epoch);
        }
    }
}
