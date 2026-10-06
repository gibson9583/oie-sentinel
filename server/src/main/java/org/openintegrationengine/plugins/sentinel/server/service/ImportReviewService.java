/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** Preview and apply share reference preparation and the existing import pipeline.
 * Fingerprints are a stale-preview guard, not an enclosing transaction. */
public final class ImportReviewService {
    private ImportReviewService() { }
    public record Remapping(String path, String type, String source, String target) { }
    public record Preview(String targetFingerprint, String planHash,
            ExportImportService.ImportResult plan, List<Remapping> remappings) { }
    public record Applied(boolean stale, Preview preview, ExportImportService.ImportResult receipt) { }

    public static Preview preview(JsonNode request) {
        requireRequest(request);
        String before = fingerprint();
        List<Remapping> remappings = new ArrayList<>();
        JsonNode prepared = prepare(request, remappings);
        var plan = ExportImportService.runReviewed(prepared, 0, true);
        if (!before.equals(fingerprint())) {
            throw new IllegalArgumentException("Target configuration changed during preview; preview again");
        }
        return new Preview(before, digest(request.path("document"), request.path("mappings")), plan, remappings);
    }

    public static Applied apply(JsonNode request, int userId) {
        requireRequest(request);
        return ConfigurationImportLock.withLock(() -> applyLocked(request, userId));
    }

    private static Applied applyLocked(JsonNode request, int userId) {
        requireRequest(request);
        // Re-run the exact plan, including reference/secret validation, before any writes.
        Preview current = preview(request);
        if (!current.targetFingerprint().equals(request.path("targetFingerprint").asText())
                || !current.planHash().equals(request.path("planHash").asText())) {
            return new Applied(true, current, null);
        }
        JsonNode prepared = prepare(request, new ArrayList<>());
        if (!current.targetFingerprint().equals(fingerprint())) return new Applied(true, preview(request), null);
        return new Applied(false, null, ExportImportService.runReviewed(prepared, userId, false));
    }

    private static void requireRequest(JsonNode request) {
        if (request == null || !request.isObject() || !request.path("document").isObject()) {
            throw new IllegalArgumentException("An import review requires a document object");
        }
        ExportImportService.validateDocumentShape(request.path("document"));
        JsonNode mappings = request.path("mappings");
        if (!mappings.isMissingNode() && !mappings.isNull() && !mappings.isObject()) {
            throw new IllegalArgumentException("Mappings must be an object keyed by CHANNEL, GROUP or TAG");
        }
        if (mappings.isObject()) mappings.fields().forEachRemaining(field -> {
            if (!Set.of("CHANNEL", "GROUP", "TAG").contains(field.getKey()) || !field.getValue().isObject()) {
                throw new IllegalArgumentException("Mapping types must be CHANNEL, GROUP or TAG objects");
            }
            field.getValue().fields().forEachRemaining(pair -> {
                if (pair.getKey().isBlank() || !pair.getValue().isTextual() || pair.getValue().asText().isBlank()) {
                    throw new IllegalArgumentException("Each mapping must have a source ID and a nonempty target ID");
                }
            });
        });
    }

    private static JsonNode prepare(JsonNode request, List<Remapping> remappings) {
        ObjectNode document = request.path("document").deepCopy();
        Map<String, Set<String>> targets = new HashMap<>();
        Set<String> channels = new HashSet<>(), groups = new HashSet<>(), tags = new HashSet<>();
        for (ChannelInfo channel : ScopeResolver.listChannels()) channels.add(channel.getChannelId());
        for (ChannelGroupInfo group : ScopeResolver.listGroups()) groups.add(group.getId());
        for (TagInfo tag : ScopeResolver.listTags()) tags.add(tag.getId());
        targets.put("CHANNEL", channels); targets.put("GROUP", groups); targets.put("TAG", tags);
        // Validate every explicit target, including unused mappings: a typo should not look accepted.
        JsonNode mapping = request.path("mappings");
        if (mapping.isObject()) mapping.fields().forEachRemaining(type -> type.getValue().fields().forEachRemaining(pair -> {
            if (!targets.get(type.getKey()).contains(pair.getValue().asText())) {
                throw new IllegalArgumentException("Mapping target does not exist for " + type.getKey() + ": " + pair.getValue().asText());
            }
        }));
        Map<String, String> parents = new HashMap<>();
        Map<Integer, String> names = new HashMap<>();
        List<Monitor> existing = MonitorService.list();
        for (Monitor monitor : existing) names.put(monitor.getId(), key(monitor.getName()));
        for (Monitor monitor : existing) parents.put(key(monitor.getName()), names.get(monitor.getSuppressedByMonitorId()));
        for (String section : List.of("monitors", "actions", "maintenanceWindows")) {
            JsonNode nodes = document.path(section);
            if (!nodes.isArray()) continue; // existing importer owns malformed array rejection
            Map<String, ObjectNode> seen = new HashMap<>();
            int position = 0;
            for (JsonNode node : nodes) {
                String prefix = section + "[" + position++ + "]";
                if (!node.isObject()) continue;
                ObjectNode item = (ObjectNode) node;
                item.remove("_importError"); // never trust client-supplied planner metadata
                String name = key(item.path("name").asText());
                if (!name.isEmpty()) {
                    ObjectNode duplicate = seen.putIfAbsent(name, item);
                    if (duplicate != null) {
                        error(item, "Duplicate name in document; remove duplicates before applying");
                        error(duplicate, "Duplicate name in document; remove duplicates before applying");
                    }
                }
                if (section.equals("monitors")) {
                    parents.put(name, key(item.path("suppressedByMonitorName").asText()));
                    if (item.hasNonNull("suppressedByMonitorId")) error(item, "Monitor dependency IDs are environment-specific; use suppressedByMonitorName instead");
                }
                String scope = item.path("scopeType").asText();
                if (targets.containsKey(scope)) remap(item, "scopeId", scope, prefix + ".scopeId", mapping, targets, remappings, item);
                if (section.equals("actions")) {
                    // Source action serials are not portable; do not silently bind to
                    // an unrelated action with the same target-server serial.
                    if (item.hasNonNull("escalateToActionId")) error(item, "Escalation action IDs are environment-specific; configure the escalation on this server after import");
                    rewriteAction(item, prefix, mapping, targets, remappings);
                }
            }
        }
        for (JsonNode node : document.path("monitors")) {
            if (!node.isObject()) continue;
            Set<String> visited = new HashSet<>(); String cursor = key(node.path("name").asText());
            while (cursor != null && !cursor.isEmpty()) {
                if (!visited.add(cursor)) { error((ObjectNode) node, "Suppression dependency chain is circular; monitor left untouched"); break; }
                cursor = parents.get(cursor);
            }
        }
        return document;
    }

    private static void rewriteAction(ObjectNode item, String prefix, JsonNode mapping,
            Map<String, Set<String>> targets, List<Remapping> remappings) {
        try {
            JsonNode config = Json.mapper().readTree(item.path("configJson").asText(""));
            if ("CHANNEL".equals(item.path("actionType").asText()) && config instanceof ObjectNode) {
                remap((ObjectNode) config, "channelId", "CHANNEL", prefix + ".configJson.channelId", mapping, targets, remappings, item);
                item.put("configJson", Json.write(config));
            }
        } catch (Exception e) { /* definition validator reports malformed config without echoing content */ }
        try {
            String text = item.path("conditionJson").asText("");
            if (text.isBlank()) return;
            JsonNode condition = Json.mapper().readTree(text);
            if (!condition.isArray()) return;
            int index = 0;
            for (JsonNode row : condition) {
                String field = row.path("field").asText().toUpperCase(Locale.ROOT);
                String type = field.equals("CHANNEL") ? "CHANNEL" : field.equals("CHANNEL_GROUP") ? "GROUP" : field.equals("CHANNEL_TAG") ? "TAG" : null;
                if (type != null && row.isObject()) {
                    JsonNode value = row.path("value");
                    String location = prefix + ".conditionJson[" + index + "].value";
                    if (value.isArray()) {
                        ArrayNode mapped = Json.mapper().createArrayNode();
                        for (JsonNode v : value) mapped.add(mapped(v.asText(), type, location, mapping, targets, remappings, item));
                        ((ObjectNode) row).set("value", mapped);
                    } else {
                        ((ObjectNode) row).put("value", mapped(value.asText(), type, location, mapping, targets, remappings, item));
                    }
                }
                index++;
            }
            item.put("conditionJson", Json.write(condition));
        } catch (Exception e) { /* shared definition validator owns parse failure */ }
    }

    private static void remap(ObjectNode owner, String field, String type, String path, JsonNode mapping,
            Map<String, Set<String>> targets, List<Remapping> remappings, ObjectNode item) {
        owner.put(field, mapped(owner.path(field).asText(), type, path, mapping, targets, remappings, item));
    }
    private static String mapped(String source, String type, String path, JsonNode mapping,
            Map<String, Set<String>> targets, List<Remapping> remappings, ObjectNode item) {
        String target = mapping.path(type).path(source).asText(source);
        if (!source.equals(target)) remappings.add(new Remapping(path, type, source, target));
        if (!targets.get(type).contains(target)) error(item, "Unresolved " + type + " reference at " + path + "; provide an explicit mapping or create the target");
        return target;
    }
    private static void error(ObjectNode item, String reason) { if (!item.has("_importError")) item.put("_importError", reason); }
    private static String key(String name) { return name == null ? "" : name.trim().toLowerCase(Locale.ROOT); }

    private static String fingerprint() {
        ObjectNode snapshot = Json.mapper().createObjectNode();
        snapshot.set("monitors", sorted(Json.mapper().valueToTree(MonitorService.list())));
        // Stored action configs include encrypted credential material. Hash them
        // only on the server so credential rotation is detected even when two
        // updates share an audit timestamp; never put them in preview/diffs.
        snapshot.set("actions", sorted(Json.mapper().valueToTree(ActionRepository.listActions(null))));
        ArrayNode windows = Json.mapper().valueToTree(MaintenanceWindowService.list());
        for (JsonNode window : windows) ((ObjectNode) window).remove("activeNow");
        snapshot.set("windows", sorted(windows));
        ArrayNode channels = Json.mapper().valueToTree(ScopeResolver.listChannels());
        for (JsonNode channel : channels) { ((ObjectNode) channel).remove("state"); ((ObjectNode) channel).remove("started"); }
        snapshot.set("channels", sorted(channels));
        for (String type : List.of("groups", "tags")) {
            ArrayNode inventory = Json.mapper().valueToTree(type.equals("groups") ? ScopeResolver.listGroups() : ScopeResolver.listTags());
            for (JsonNode item : inventory) if (item.path("channelIds").isArray()) ((ObjectNode) item).set("channelIds", sorted(item.get("channelIds")));
            snapshot.set(type, sorted(inventory));
        }
        return digest(snapshot);
    }
    private static ArrayNode sorted(JsonNode values) {
        List<JsonNode> nodes = new ArrayList<>(); values.forEach(nodes::add);
        nodes.sort(Comparator.comparing(node -> Json.write(canonical(node))));
        ArrayNode array = Json.mapper().createArrayNode(); nodes.forEach(array::add); return array;
    }
    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = Json.mapper().createObjectNode();
            List<String> keys = new ArrayList<>(); node.fieldNames().forEachRemaining(keys::add); Collections.sort(keys);
            keys.forEach(key -> result.set(key, canonical(node.get(key)))); return result;
        }
        if (node.isArray()) { ArrayNode result = Json.mapper().createArrayNode(); node.forEach(value -> result.add(canonical(value))); return result; }
        return node;
    }
    private static String digest(JsonNode... nodes) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (JsonNode node : nodes) { hash.update(Json.write(canonical(node)).getBytes(StandardCharsets.UTF_8)); hash.update((byte) 0); }
            return HexFormat.of().formatHex(hash.digest());
        } catch (Exception e) { throw new IllegalStateException("Could not fingerprint import configuration", e); }
    }
}
