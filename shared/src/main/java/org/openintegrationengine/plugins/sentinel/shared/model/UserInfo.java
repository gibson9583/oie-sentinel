/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * A slim view of one engine user for Sentinel's display lookups
 * ({@code GET /core/users}) — resolving audit ids like
 * {@link AlertEvent#getAcknowledgedBy()} to a human-readable name.
 *
 * <p>Deliberately carries only id and username: Sentinel's UI needs a name
 * next to "acknowledged by", never emails, roles, or login state, and the
 * engine's own {@code /users} endpoint requires user-management permission
 * that a Sentinel viewer may not hold. Response-only; never persisted.</p>
 */
public class UserInfo {

    private Integer userId;
    private String username;

    public UserInfo() {
    }

    /**
     * @return the engine user id
     */
    public Integer getUserId() {
        return userId;
    }

    /**
     * @param userId the engine user id
     */
    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    /**
     * @return the user's login name
     */
    public String getUsername() {
        return username;
    }

    /**
     * @param username the user's login name
     */
    public void setUsername(String username) {
        this.username = username;
    }
}
