/* OIE Sentinel — MPL 2.0. */
package org.openintegrationengine.plugins.sentinel.shared.model;

/** Unsaved metric threshold candidate for one explicit channel; epoch-millis
 * bounds. Replay never saves a monitor or mutates its trigger/lifecycle. */
public class ActivityReplayRequest {
    private Monitor monitor;
    private String channelId;
    private Long from;
    private Long to;
    private Integer stepSeconds;
    private Integer maxSampleGapSeconds;
    public Monitor getMonitor() { return monitor; }
    public void setMonitor(Monitor value) { monitor = value; }
    public String getChannelId() { return channelId; }
    public void setChannelId(String value) { channelId = value; }
    public Long getFrom() { return from; }
    public void setFrom(Long value) { from = value; }
    public Long getTo() { return to; }
    public void setTo(Long value) { to = value; }
    public Integer getStepSeconds() { return stepSeconds; }
    public void setStepSeconds(Integer value) { stepSeconds = value; }
    public Integer getMaxSampleGapSeconds() { return maxSampleGapSeconds; }
    public void setMaxSampleGapSeconds(Integer value) { maxSampleGapSeconds = value; }
}
