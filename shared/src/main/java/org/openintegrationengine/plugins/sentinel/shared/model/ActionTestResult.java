/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * Outcome of sending a real test notification through an alert action
 * ({@code POST /actions/{id}/_test}) with a synthetic payload.
 *
 * <p>Exists so an operator can verify SMTP/SNS/channel wiring at
 * configuration time rather than when a real alert depends on it. The
 * delivery genuinely happens — this is not a dry run — so the result is a
 * simple success flag plus whatever the sender reported. Response-only;
 * never persisted (test sends are deliberately absent from the dispatch
 * log, which tracks only real alert traffic).</p>
 */
public class ActionTestResult {

    private boolean success;
    private String message;

    public ActionTestResult() {
    }

    /**
     * @return {@code true} if the test notification was handed off to its
     *         transport without error
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * @param success whether the test notification was delivered without error
     */
    public void setSuccess(boolean success) {
        this.success = success;
    }

    /**
     * @return human-readable outcome detail — a confirmation on success, the
     *         transport's error message on failure
     */
    public String getMessage() {
        return message;
    }

    /**
     * @param message human-readable outcome detail
     */
    public void setMessage(String message) {
        this.message = message;
    }
}
