package org.openintegrationengine.plugins.sentinel.shared.model;

/** Display context never replaces the immutable transport-attempt snapshot. */
public record DeliveryAttempt(ActionDispatchLog attempt, String channelId, int monitorId,
        String eventStatus, String actionName, String actionContextSource, boolean actionDeleted) { }
