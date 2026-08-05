/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The one shared Jackson {@link ObjectMapper} for the whole plugin.
 *
 * <p>Sentinel deliberately bypasses the engine's XStream/StaxON JSON pipeline
 * (root-key wrapping, single-element-list collapse, security allow-listing)
 * by returning raw JSON strings from every servlet method — the
 * community-store pattern. That makes this mapper the single authority on
 * Sentinel's wire shape, so it must be configured once, here, and never
 * per-call: {@link JavaTimeModule} is registered and
 * {@code WRITE_DATES_AS_TIMESTAMPS} disabled so {@link java.time.Instant}
 * fields serialize as ISO-8601 UTC strings (what the web client's date
 * handling expects), and {@code FAIL_ON_UNKNOWN_PROPERTIES} is disabled so a
 * newer web client sending an extra field does not break an older server —
 * the same "unknown fields tolerated" stance the monitor-config validation
 * takes.</p>
 *
 * <p>{@code ObjectMapper} is thread-safe once configured, so a single static
 * instance shared across the collector/evaluator jobs and every concurrent
 * REST request is both safe and the cheapest option (mapper construction is
 * expensive; serializer caches are per-instance).</p>
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Json() {
    }

    /**
     * Returns the shared, fully configured mapper — for callers that need
     * more than {@link #write(Object)}/{@link #read(String, Class)}, e.g.
     * building an {@code ObjectNode} for a value-JSON blob or
     * {@code readTree} over a config document.
     *
     * @return the singleton mapper; callers must not reconfigure it
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * Serializes any object to a JSON string. Serialization of Sentinel's
     * plain-bean DTOs cannot legitimately fail (no cycles, no unserializable
     * field types), so the checked {@link JsonProcessingException} is wrapped
     * in a {@link RuntimeException} rather than forcing every call site —
     * evaluators building value JSON, senders building payloads, the servlet
     * building responses — to declare or swallow an impossibility.
     *
     * @param value the object to serialize; may be {@code null} (serializes
     *              as JSON {@code null})
     * @return the JSON representation
     * @throws RuntimeException if Jackson fails to serialize (indicates a
     *                          programming error in the DTO, not bad input)
     */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize " + (value != null ? value.getClass().getName() : "null")
                    + " to JSON", e);
        }
    }

    /**
     * Deserializes a JSON string into the given type. Bad JSON here almost
     * always means a malformed REST request body, so the failure is surfaced
     * as {@link IllegalArgumentException} with a human-readable message —
     * the exact exception type the servlet layer already maps to HTTP 400,
     * so call sites need no extra translation.
     *
     * @param json the JSON document to parse
     * @param type the target type
     * @return the deserialized instance
     * @throws IllegalArgumentException if the input is not valid JSON for
     *                                  the target type
     */
    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON for " + type.getSimpleName() + ": "
                    + e.getOriginalMessage(), e);
        }
    }
}
