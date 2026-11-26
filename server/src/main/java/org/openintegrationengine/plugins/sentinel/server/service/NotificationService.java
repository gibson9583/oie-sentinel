/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.util.List;
import java.util.Set;

import org.openintegrationengine.plugins.sentinel.server.alert.MatchedLines;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEventFilter;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertKind;
import org.openintegrationengine.plugins.sentinel.shared.model.PagedResult;

/**
 * The Notifications history: alert events of kind NOTIFICATION, newest first.
 * Stored matches are returned only while their monitor includes them, as in
 * {@link MatchedLines#visibleDetails}.
 */
public final class NotificationService {

    private NotificationService() {
    }

    /**
     * @param authorizedChannelIds {@code null} for an unrestricted caller; a
     *        restricted caller sees only notifications on those channels, so
     *        no Log notification, which has none
     */
    public static PagedResult<AlertEvent> list(Integer monitorId, Long fromEpochMillis, Long toEpochMillis,
            int page, int pageSize, Set<String> authorizedChannelIds) {
        AlertEventFilter filter = ProblemService.buildFilter(null, null, null, monitorId, null, null,
                fromEpochMillis, toEpochMillis, null, "opened_time", "DESC", page, pageSize);
        filter.setKind(AlertKind.NOTIFICATION);
        if (authorizedChannelIds != null) {
            if (authorizedChannelIds.isEmpty()) {
                return new PagedResult<>(List.of(), 0L, filter.getPage(), filter.getPageSize());
            }
            filter.setChannelIdIn(List.copyOf(authorizedChannelIds));
        }
        PagedResult<AlertEvent> result = AlertEventRepository.listAlertEvents(filter);
        MatchedLines.applyVisibleDetails(result.items());
        return result;
    }
}
