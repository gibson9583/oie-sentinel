/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.openintegrationengine.plugins.sentinel.server.db.ActionDispatchLogRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** Existing attempts and pending lifecycle accounting are deliberately separate reads. */
public final class DeliveryInboxService {
    private DeliveryInboxService() { }
    public static PagedResult<DeliveryAttempt> attempts(Set<String> authorizedChannels, String channelsCsv,
            Integer actionId, String transport, Long eventId, String phase, Boolean success,
            Long from, Long to, int page, int pageSize) {
        int size = size(pageSize), currentPage = page(page);
        range(from,to);
        Map<String,Object> filter = new HashMap<>();
        filter.put("channelIdIn", channels(authorizedChannels,channelsCsv));
        filter.put("actionId", actionId); filter.put("eventId",eventId);
        filter.put("transport", choice(transport,Set.of("EMAIL","CHANNEL","SNS","WEBHOOK","UNKNOWN"),"transport"));
        filter.put("phase",choice(phase,Set.of("PROBLEM","RESOLVED","UNKNOWN"),"phase"));
        filter.put("success",success);filter.put("from",timestamp(from));filter.put("to",timestamp(to));
        filter.put("offset",currentPage*size);filter.put("limit",size);
        if (actionId != null && actionId <= 0 || eventId != null && eventId <= 0) throw new IllegalArgumentException("Action/event IDs must be positive");
        return ActionDispatchLogRepository.listDeliveries(filter,currentPage,size);
    }
    public static PagedResult<AlertEvent> pending(Set<String> authorizedChannels, String channelsCsv,
            Long from, Long to, int page, int pageSize) {
        range(from,to);
        List<String> visible = channels(authorizedChannels,channelsCsv);
        int size=size(pageSize), currentPage=page(page);
        if (visible != null && visible.isEmpty()) return new PagedResult<>(List.of(),0,currentPage,size);
        AlertEventFilter filter=ProblemService.buildFilter(null,null,null,null,null,null,from,to,null,"opened_time","DESC",currentPage,size);
        filter.setChannelIdIn(visible);filter.setPendingOnly(true);
        return ProblemService.list(filter);
    }
    private static int size(int value) { return value<=0?25:Math.min(value,100); }
    private static int page(int value) { return Math.max(0,Math.min(value,Integer.MAX_VALUE/100)); }
    private static List<String> channels(Set<String> authorized,String csv) {
        Set<String> requested=new LinkedHashSet<>();
        if(csv!=null) for(String id:csv.split(",")) if(!id.trim().isEmpty()) requested.add(id.trim());
        if(authorized==null)return requested.isEmpty()?null:new ArrayList<>(requested);
        if(requested.isEmpty())return new ArrayList<>(authorized);
        requested.retainAll(authorized);return new ArrayList<>(requested);
    }
    private static String choice(String value,Set<String> choices,String label) {
        if(value==null||value.isBlank())return null;String selected=value.trim().toUpperCase(Locale.ROOT);
        if(!choices.contains(selected))throw new IllegalArgumentException("Invalid "+label);return selected;
    }
    private static Timestamp timestamp(Long millis) { return millis==null?null:Timestamp.from(Instant.ofEpochMilli(millis)); }
    private static void range(Long from,Long to) { if(from!=null&&to!=null&&from>to)throw new IllegalArgumentException("From must be at or before To"); }
}
