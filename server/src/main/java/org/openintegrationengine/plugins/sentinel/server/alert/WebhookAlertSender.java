/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import org.openintegrationengine.plugins.sentinel.server.service.SettingsCrypto;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;

/**
 * Delivers alert notifications as an HTTP request to an operator-supplied
 * URL — the short path to Slack, Teams, PagerDuty, Alertmanager or any other
 * endpoint that accepts a JSON POST.
 *
 * <p>{@link ChannelAlertSender} could already do this, and remains the answer
 * whenever the delivery needs transformation, retries, or a non-HTTP
 * transport. But standing up, deploying and version-controlling a whole OIE
 * channel purely to POST one JSON document is a great deal of ceremony for
 * the most common integration there is, and every operator who did it built
 * the same channel slightly differently. This transport is that one case,
 * done once, with the security properties written down.</p>
 *
 * <h2>Config shape</h2>
 *
 * <p>{@code {url, method?, headers?, bodyTemplate?, timeoutSeconds?,
 * allowInsecure?, allowPrivateNetwork?}}:</p>
 * <ul>
 *   <li>{@code url} — required, absolute, {@code https} unless
 *       {@code allowInsecure}.</li>
 *   <li>{@code method} — {@code POST} (default), {@code PUT} or
 *       {@code PATCH}. Body-less verbs are deliberately absent: an alert
 *       notification carries a payload, and permitting {@code GET} would turn
 *       the action into a general-purpose request forger for no delivery
 *       benefit.</li>
 *   <li>{@code headers} — a JSON object of header name to value. Values
 *       support the same template tokens as the body. An object (not a list)
 *       because duplicate header names have no meaning for a webhook and the
 *       object shape makes that structurally true.</li>
 *   <li>{@code bodyTemplate} — the request body, with {@code ${...}} tokens
 *       substituted. Empty means "send the whole {@link AlertPayload} as
 *       JSON", the same document {@link ChannelAlertSender} routes.</li>
 *   <li>{@code timeoutSeconds} — whole-exchange wall clock,
 *       {@value #MIN_TIMEOUT_SECONDS}–{@value #MAX_TIMEOUT_SECONDS}, default
 *       {@value #DEFAULT_TIMEOUT_SECONDS}.</li>
 *   <li>{@code allowInsecure} / {@code allowPrivateNetwork} — the two egress
 *       opt-ins, both defaulting false. See {@link WebhookTargetGuard}.</li>
 * </ul>
 *
 * <h2>Security posture</h2>
 *
 * <p>The SSRF story — why an alert transport that dials arbitrary URLs is a
 * credential-exfiltration hazard on any cloud instance, what is blocked, and
 * the residual DNS-rebinding window this design accepts — is documented in
 * full on {@link WebhookTargetGuard}. Read that class before changing
 * anything here. Two decisions in <em>this</em> class are load-bearing parts
 * of it:</p>
 *
 * <ol>
 *   <li><b>Redirects are never followed.</b> Following one would connect to a
 *       destination that never passed the address check, which is by far the
 *       cheapest way to reach {@code 169.254.169.254} — no DNS control
 *       needed, just a {@code 302}. Re-validating every hop would also work
 *       and is what a general HTTP library would do, but "don't follow" is
 *       less code, has no ordering subtleties, and cannot be got wrong by a
 *       later edit. A {@code 3xx} is reported as a delivery failure naming
 *       the status, so an operator who genuinely needs a redirect target
 *       simply configures the final URL.</li>
 *   <li><b>No proxy.</b> The client is pinned to
 *       {@link HttpClient.Builder#NO_PROXY} rather than inheriting the JVM's
 *       default proxy selector (which honours {@code https.proxyHost} and
 *       friends, so an engine configured for a corporate egress proxy would
 *       silently get one here). A proxy would
 *       resolve the hostname itself, on its own side of the network, which
 *       would make the address check in {@link WebhookTargetGuard} decorative
 *       — we would have validated addresses nobody then connects to. The
 *       cost is that a webhook cannot traverse a corporate egress proxy; the
 *       CHANNEL action, which goes through the engine's own HTTP Sender and
 *       its proxy settings, is the escape hatch for that deployment.</li>
 * </ol>
 *
 * <p>Header values named like credentials are stored encrypted and redacted
 * on read — see {@link #isSecretHeaderName(String)}, which is the single
 * definition shared by {@code ActionService}'s redaction path and the
 * just-in-time decrypt below, so the two can never disagree about which
 * headers are ciphertext.</p>
 *
 * <p>Neither the URL path nor a request header ever appears in a failure
 * message. Dispatch-log rows are readable at View Monitoring, the plugin's
 * most widely granted permission, and a Slack-style webhook URL <em>is</em>
 * the credential — its path segment is the bearer token. Failures name the
 * target as {@code scheme://host[:port]} only.</p>
 */
public final class WebhookAlertSender implements AlertSender {

    /** Lower bound for the configured wall clock; below this nothing completes. */
    static final int MIN_TIMEOUT_SECONDS = 1;

    /**
     * Upper bound for the configured wall clock. The dispatch pool is four
     * threads wide (see {@link ActionDispatcher}), so four simultaneous
     * webhooks against a black-holed endpoint stall every other transport for
     * this long. Thirty seconds is generous for an HTTP POST and is the point
     * past which a notification has missed the point of being an alert.
     */
    static final int MAX_TIMEOUT_SECONDS = 30;

    /** Default wall clock when the action does not configure one. */
    static final int DEFAULT_TIMEOUT_SECONDS = 10;

    /**
     * Fixed ceiling on the TCP/TLS handshake, inside the overall budget. It
     * is per-client rather than per-request (the JDK builder has no
     * per-request connect timeout) which is why it is a constant: the client
     * is shared across every webhook action.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * How much response body is read before the exchange is cancelled. The
     * body is wanted only as a diagnostic — Slack's "invalid_payload",
     * PagerDuty's validation errors — so a few kilobytes is generous, and a
     * hard cap is required rather than nice-to-have: without one, an endpoint
     * that answers a 4xx with a multi-gigabyte body would be answered by this
     * sender buffering all of it on a dispatch thread.
     */
    private static final int MAX_RESPONSE_BYTES = 4096;

    /**
     * How much of that body reaches the dispatch-log failure reason. Much
     * smaller than the read cap because the log row is readable at View
     * Monitoring and is the one channel by which anything the remote endpoint
     * says gets back to a human — see the class Javadoc.
     */
    private static final int MAX_RESPONSE_SNIPPET = 200;

    /** Body-carrying verbs only; see the class Javadoc for why GET is absent. */
    static final Set<String> ALLOWED_METHODS = Set.of("POST", "PUT", "PATCH");

    /**
     * Headers {@code java.net.http} refuses to let a caller set, because it
     * owns them itself (framing, connection management, virtual host). Setting
     * one throws {@link IllegalArgumentException} from deep inside the request
     * builder; rejecting them here instead turns that into an operator-readable
     * message at save time. Lower-cased for comparison.
     */
    static final Set<String> RESTRICTED_HEADERS =
            Set.of("connection", "content-length", "expect", "host", "upgrade");

    /** Sent when the action configures no {@code Content-Type} of its own. */
    private static final String DEFAULT_CONTENT_TYPE = "application/json; charset=utf-8";

    /**
     * Which header <em>names</em> mark their value a secret — encrypted at
     * rest and redacted on read. Matched with {@code find()}, so the anchored
     * alternatives are exact names and the rest are substrings.
     *
     * <p>Deliberately over-inclusive. The cost of treating a non-secret
     * header as a secret is that the operator re-types it if they ever need
     * to change it and cannot read it back afterwards; the cost of missing a
     * real one is a bearer token sitting in plaintext in the database and
     * being handed to every View Monitoring user through the actions list.
     * Those costs are not remotely symmetric, so anything that looks like a
     * credential is treated as one. The names left out are the ones that
     * genuinely never carry a secret and that operators do need to read —
     * {@code Content-Type}, {@code Accept}, {@code User-Agent},
     * {@code Idempotency-Key} and similar.</p>
     *
     * <p>{@code Authorization} is called out by name in the plan for this
     * feature and is the first alternative here; {@code Cookie} and
     * {@code Proxy-Authorization} join it because they are credential
     * carriers whose names contain none of the giveaway substrings.</p>
     */
    private static final Pattern SECRET_HEADER_NAME = Pattern.compile(
            "^(authorization|proxy-authorization|cookie)$"
                    + "|token|secret|password|passwd|signature|credential|api[-_]?key",
            Pattern.CASE_INSENSITIVE);

    /**
     * Holder for the one shared {@link HttpClient}.
     *
     * <p><b>Shared, not per-send</b> — the opposite of what
     * {@link SnsAlertSender} does with its AWS clients, for a concrete reason
     * rather than taste. A {@code java.net.http.HttpClient} owns a selector
     * thread and an executor and has no {@code close()} before Java 21, so a
     * per-send client would strand a thread per notification until the GC got
     * around to reclaiming it — during an alert storm, precisely when it must
     * not. The client is immutable and documented thread-safe, everything
     * per-action lives on the {@link HttpRequest}, and connection reuse is a
     * small bonus (a pooled connection goes to an address that passed the
     * guard when it was established).</p>
     *
     * <p><b>Lazy</b> — via the holder idiom rather than a static field on the
     * sender, so construction happens on the first webhook send instead of
     * when {@link ActionDispatcher} initializes its senders. An engine with no
     * webhook actions therefore carries no selector thread at all (matching
     * how the dispatch pool creates its threads on demand), and in the
     * unlikely event that building a client fails, the failure is one failed
     * webhook dispatch rather than a {@code NoClassDefFoundError} that takes
     * every transport down with it.</p>
     *
     * <p>Pinned to HTTP/1.1: the JDK default would negotiate HTTP/2, whose
     * only real benefit is multiplexing that a one-request-per-alert
     * transport never uses, while adding an ALPN/upgrade dance in front of
     * endpoints that vary in how well they handle it. A notification's
     * request shape should be boring.</p>
     */
    private static final class ClientHolder {
        private static final HttpClient CLIENT = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * Renders and sends one HTTP request.
     *
     * <p>Ordering matters and is not incidental: the URL is validated, then
     * the target's resolved addresses are checked, and only then is anything
     * connected. The address check is the last thing before the request goes
     * out precisely so its answer is as fresh as it can be.</p>
     *
     * <p>Non-2xx responses throw, per the {@link AlertSender} contract — a
     * webhook that answered {@code 403} did not deliver the alert, and
     * recording it as a success would silently break the operator's paging.</p>
     */
    @Override
    public void send(Action action, AlertEvent event, AlertPayload payload) throws Exception {
        String name = action.getName();
        JsonNode config = readConfig(action);

        boolean allowInsecure = config.path("allowInsecure").asBoolean(false);
        boolean allowPrivateNetwork = config.path("allowPrivateNetwork").asBoolean(false);
        URI uri = WebhookTargetGuard.validateUrl(name, text(config, "url"), allowInsecure);

        String method = method(name, text(config, "method"));
        int timeoutSeconds = timeoutSeconds(config);
        List<String[]> headers = renderHeaders(name, config.get("headers"), payload);
        String body = renderBody(config, headers, payload);

        // Last gate before the socket. See WebhookTargetGuard for why this
        // lives here and not at save time, and for the residual rebinding
        // window between this call and the client's own resolution.
        WebhookTargetGuard.assertTargetAllowed(name, uri, allowPrivateNetwork);

        HttpRequest request = buildRequest(name, uri, method, headers, body, timeoutSeconds);
        HttpResponse<String> response = exchange(name, uri, request, timeoutSeconds);

        int status = response.statusCode();
        if (status < 200 || status > 299) {
            throw new Exception("Webhook action '" + name + "' to " + target(uri) + " returned HTTP "
                    + status + (status >= 300 && status < 400
                            ? " (redirects are not followed — configure the final URL directly)" : "")
                    + snippet(response.body()));
        }
    }

    // ========== save-time validation ==========

    /**
     * Save-time validation of a WEBHOOK action's config, called by
     * {@code ActionService.validateConfig}. Lives here rather than in the
     * service because everything it checks is a property of this transport —
     * the schemes it will dial, the verbs it will send, the timeout the
     * dispatch pool can afford, the header names the HTTP client permits —
     * and a copy of those rules in the service would be a second place for
     * them to drift.
     *
     * <p>Throws {@link IllegalArgumentException} (mapped to HTTP 400 by the
     * servlet), unlike the send path's checked {@link Exception}s, which
     * become dispatch-log failure reasons.</p>
     *
     * <p><b>Not</b> checked here: whether the host resolves to a permitted
     * address. That check is only meaningful at the moment of connecting —
     * see {@link WebhookTargetGuard} — and doing a DNS lookup on the admin
     * API request thread would let a slow resolver hold a Jetty worker. The
     * one exception is a literal IP in the URL, which needs no lookup and so
     * is rejected immediately; everything else is answered by the "Send test"
     * button, which performs a real delivery.</p>
     *
     * @param actionName the action's name, for the operator-facing message
     * @param config     the parsed config object
     * @throws IllegalArgumentException if any part of the config is unusable
     */
    public static void validateConfig(String actionName, JsonNode config) {
        boolean allowInsecure = config.path("allowInsecure").asBoolean(false);
        boolean allowPrivateNetwork = config.path("allowPrivateNetwork").asBoolean(false);

        URI uri = WebhookTargetGuard.validateUrl(actionName, text(config, "url"), allowInsecure);
        WebhookTargetGuard.assertLiteralTargetAllowed(actionName, uri, allowPrivateNetwork);

        String method = text(config, "method");
        if (method != null && !ALLOWED_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("WEBHOOK actions support only "
                    + "POST, PUT or PATCH (got '" + AlertSender.singleLine(method) + "')");
        }

        JsonNode timeout = config.get("timeoutSeconds");
        if (timeout != null && !timeout.isNull()) {
            int seconds = timeout.asInt(-1);
            if (seconds < MIN_TIMEOUT_SECONDS || seconds > MAX_TIMEOUT_SECONDS) {
                throw new IllegalArgumentException("WEBHOOK timeoutSeconds must be between "
                        + MIN_TIMEOUT_SECONDS + " and " + MAX_TIMEOUT_SECONDS);
            }
        }

        JsonNode headers = config.get("headers");
        if (headers == null || headers.isNull()) {
            return;
        }
        if (!headers.isObject()) {
            throw new IllegalArgumentException("WEBHOOK headers must be a JSON object of name to value");
        }
        Iterator<String> names = headers.fieldNames();
        while (names.hasNext()) {
            String headerName = names.next().trim();
            if (headerName.isEmpty()) {
                continue;
            }
            if (!isValidHeaderName(headerName)) {
                throw new IllegalArgumentException("'" + AlertSender.singleLine(headerName)
                        + "' is not a valid HTTP header name");
            }
            if (RESTRICTED_HEADERS.contains(headerName.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("The '" + headerName + "' header is managed by the "
                        + "HTTP client and cannot be set on a webhook action");
            }
        }
    }

    /**
     * Whether a header name marks its value a secret — the single definition
     * used both by {@code ActionService}, to decide what to encrypt at rest
     * and mask on read, and by {@link #renderHeaders} here, to decide what to
     * decrypt on the way out. See {@link #SECRET_HEADER_NAME} for the policy
     * and why it errs wide.
     *
     * @param headerName the configured header name; {@code null} is not secret
     * @return {@code true} when the value must be encrypted and redacted
     */
    public static boolean isSecretHeaderName(String headerName) {
        return headerName != null && SECRET_HEADER_NAME.matcher(headerName.trim()).find();
    }

    // ========== request construction ==========

    /**
     * Assembles the {@link HttpRequest}. Header names are validated here as
     * well as at save time, because a config row can predate the validation
     * or have been hand-edited, and an {@link IllegalArgumentException} from
     * the JDK builder would surface to the operator as an unreadable internal
     * message instead of a reason they can act on.
     */
    private static HttpRequest buildRequest(String actionName, URI uri, String method,
            List<String[]> headers, String body, int timeoutSeconds) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                // Belt and braces alongside the wall clock in exchange(): this
                // lets the JDK abort the exchange itself rather than leaving a
                // cancelled future's connection to be torn down.
                .timeout(Duration.ofSeconds(timeoutSeconds));

        boolean hasContentType = false;
        for (String[] header : headers) {
            String headerName = header[0];
            assertHeaderNameUsable(actionName, headerName);
            if ("content-type".equals(headerName.toLowerCase(Locale.ROOT))) {
                hasContentType = true;
            }
            try {
                builder.header(headerName, header[1]);
            } catch (IllegalArgumentException e) {
                // The JDK rejects header values outside ISO-8859-1, which a
                // rendered token reaches easily — a monitor named with an em
                // dash is enough. The JDK's own message quotes the offending
                // value, and this text becomes a dispatch-log row readable at
                // View Monitoring, so the value is deliberately not echoed:
                // the header could be the Authorization one.
                throw new Exception("Webhook action '" + actionName + "' produced a value for header '"
                        + headerName + "' that cannot be sent (HTTP header values are limited to "
                        + "Latin-1 text); check the template tokens it substitutes");
            }
        }
        if (!hasContentType) {
            builder.header("Content-Type", DEFAULT_CONTENT_TYPE);
        }
        return builder.build();
    }

    /**
     * Runs the exchange under a hard wall clock and hands back the response.
     *
     * <p>{@code sendAsync} + a bounded {@code get} rather than the blocking
     * {@code send}: {@link HttpRequest#timeout} bounds the arrival of the
     * response, but the guarantee this sender needs is stronger — that a
     * dispatch worker is released after {@code timeoutSeconds} no matter what
     * the far end does, including dribbling a body one byte at a time. The
     * explicit {@code cancel(true)} on expiry is what makes that true rather
     * than merely abandoning the future while its exchange keeps running.</p>
     */
    private static HttpResponse<String> exchange(String actionName, URI uri, HttpRequest request,
            int timeoutSeconds) throws Exception {
        CompletableFuture<HttpResponse<String>> future =
                ClientHolder.CLIENT.sendAsync(request, info -> new BoundedBodySubscriber());
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new Exception("Webhook action '" + actionName + "' to " + target(uri)
                    + " did not complete within " + timeoutSeconds + "s");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new Exception("Webhook action '" + actionName + "' to " + target(uri)
                    + " was interrupted (server shutting down?)");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String detail = cause.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = cause.getClass().getSimpleName();
            }
            throw new Exception("Webhook action '" + actionName + "' to " + target(uri) + " failed: "
                    + AlertSender.singleLine(detail), cause);
        }
    }

    // ========== headers ==========

    /**
     * Resolves the configured headers into ready-to-send name/value pairs:
     * decrypt if the name marks it a secret, substitute template tokens, then
     * flatten to one line.
     *
     * <p>The {@link AlertSender#singleLine} pass is not optional and is the
     * reason that helper exists in the shared interface rather than in the
     * email sender. Header values here are written into a wire protocol where
     * a CR or LF is a structural delimiter, and the values are built from
     * operator-supplied text (monitor names, evaluator messages). Unlike the
     * mail subject — which JavaMail happens to encode — nothing downstream of
     * this method would sanitize a newline, so a monitor named with a CRLF
     * would inject a header. The JDK's request builder does reject some
     * control characters, but "the library currently rejects it" is not a
     * property to depend on for a request-splitting defence.</p>
     *
     * <p>The flattening applies to decrypted secret values too. A credential
     * containing a newline is not a valid header value in the first place, so
     * there is nothing legitimate to lose, and exempting secrets would create
     * exactly one unsanitized path.</p>
     *
     * <p>Returned as a list of two-element arrays rather than a map so
     * iteration order is the operator's configured order, which is what the
     * editor shows them.</p>
     */
    private static List<String[]> renderHeaders(String actionName, JsonNode headers,
            AlertPayload payload) throws Exception {
        List<String[]> rendered = new ArrayList<>();
        if (headers == null || headers.isNull()) {
            return rendered;
        }
        if (!headers.isObject()) {
            throw new Exception("Webhook action '" + actionName
                    + "' has a 'headers' value that is not a JSON object");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = headers.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String headerName = field.getKey() == null ? "" : field.getKey().trim();
            if (headerName.isEmpty()) {
                continue;
            }
            JsonNode valueNode = field.getValue();
            String value = valueNode == null || valueNode.isNull() ? "" : valueNode.asText("");
            if (value.isEmpty()) {
                continue;
            }
            if (isSecretHeaderName(headerName)) {
                // Stored ciphertext; the plaintext exists only inside this
                // send, exactly as SnsAlertSender treats secretAccessKey —
                // never in a field or a DTO that could be serialized.
                try {
                    value = SettingsCrypto.decrypt(value);
                } catch (RuntimeException e) {
                    // The stored value was written by something other than
                    // ActionService (a hand-edited row) or under a different
                    // keystore. Name the header, not the value: this message
                    // becomes a dispatch-log row readable at View Monitoring.
                    throw new Exception("Webhook action '" + actionName + "' could not decrypt the '"
                            + headerName + "' header; re-enter it on the action to fix this", e);
                }
                if (value == null) {
                    continue;
                }
            }
            rendered.add(new String[] { headerName, AlertSender.singleLine(render(value, payload, false)) });
        }
        return rendered;
    }

    /**
     * Rejects header names the JDK client owns, or that are not valid HTTP
     * tokens. A name with a colon or a space would be a request-splitting
     * attempt, not a typo.
     */
    static void assertHeaderNameUsable(String actionName, String headerName) throws Exception {
        if (!isValidHeaderName(headerName)) {
            throw new Exception("Webhook action '" + actionName + "' has an invalid header name '"
                    + AlertSender.singleLine(headerName) + "'");
        }
        if (RESTRICTED_HEADERS.contains(headerName.toLowerCase(Locale.ROOT))) {
            throw new Exception("Webhook action '" + actionName + "' cannot set the '" + headerName
                    + "' header; it is managed by the HTTP client");
        }
    }

    /** RFC 7230 {@code token}: the only characters legal in a header field name. */
    static boolean isValidHeaderName(String headerName) {
        if (headerName == null || headerName.isEmpty()) {
            return false;
        }
        for (int i = 0; i < headerName.length(); i++) {
            char c = headerName.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    // ========== body and template rendering ==========

    /**
     * Renders the request body. An absent template sends the full
     * {@link AlertPayload} as JSON — the same document the CHANNEL transport
     * routes — so a webhook that just wants the event needs no template at
     * all.
     */
    private static String renderBody(JsonNode config, List<String[]> headers, AlertPayload payload) {
        String template = text(config, "bodyTemplate");
        if (template == null) {
            return Json.write(payload);
        }
        return render(template, payload, jsonContentType(headers));
    }

    /**
     * Whether the outgoing body is JSON, which decides how substituted values
     * are escaped. Absent {@code Content-Type} counts as JSON because that is
     * what {@link #DEFAULT_CONTENT_TYPE} will send.
     */
    private static boolean jsonContentType(List<String[]> headers) {
        for (String[] header : headers) {
            if ("content-type".equals(header[0].toLowerCase(Locale.ROOT))) {
                return header[1].toLowerCase(Locale.ROOT).contains("json");
            }
        }
        return true;
    }

    /**
     * Substitutes the supported {@code ${var}} tokens — the exact set
     * {@code EmailAlertSender.render} supports (including
     * {@code ${runbookUrl}}, so a Slack or PagerDuty payload can carry the
     * link straight to the responder), plus {@code ${valueJson}} (the
     * evaluator's measurement evidence) and {@code ${alertEventId}} (so a
     * receiving system can correlate a page back to a Sentinel problem, and
     * deduplicate repeat sends of the same alert). Plain per-token
     * {@code String.replace} for the same reason the email sender uses it:
     * unknown tokens stay verbatim in the body — visible to the operator
     * testing the action — because a template typo must never cost the
     * notification.
     *
     * <p><b>JSON escaping.</b> When the body is JSON (the default), scalar
     * values are escaped as JSON string content before substitution.
     * Otherwise the near-universal template {@code {"text":"${message}"}}
     * would emit invalid JSON the first time an evaluator message contained a
     * quote or a newline, and the endpoint would answer 400 — a lost
     * notification caused by nothing the operator did wrong. {@code ${valueJson}}
     * is the deliberate exception: it is already a JSON document and is meant
     * to be embedded unquoted, as in {@code {"details": ${valueJson}}}.</p>
     *
     * <p>Header values render with {@code escapeJson} false — a header is not
     * a JSON document — and are flattened by the caller instead. That applies
     * to {@code ${runbookUrl}} exactly as to every other scalar: it is a
     * save-time-validated URL rather than free text, but the flattening in
     * {@link #renderHeaders} is applied to the whole rendered value and has no
     * per-token exemptions, which is the property that makes it impossible to
     * add a token that quietly skips it.</p>
     */
    private static String render(String template, AlertPayload payload, boolean escapeJson) {
        String valueJson = payload.getValueJson();
        return template
                .replace("${monitorName}", scalar(payload.getMonitorName(), escapeJson))
                .replace("${channelName}", scalar(payload.getChannelName(), escapeJson))
                .replace("${severity}", scalar(
                        payload.getSeverity() != null ? payload.getSeverity().name() : "", escapeJson))
                .replace("${status}", scalar(payload.getEventType(), escapeJson))
                .replace("${message}", scalar(payload.getMessage(), escapeJson))
                .replace("${runbookUrl}", scalar(payload.getRunbookUrl(), escapeJson))
                .replace("${alertEventId}", String.valueOf(payload.getAlertEventId()))
                // Raw on purpose: already a JSON document. "null" (not empty)
                // keeps `{"details": ${valueJson}}` valid when the evaluator
                // recorded no value.
                .replace("${valueJson}", valueJson == null || valueJson.isBlank() ? "null" : valueJson);
    }

    /** One substituted scalar: null-safe, and JSON-string-escaped for JSON bodies. */
    private static String scalar(String value, boolean escapeJson) {
        String safe = value != null ? value : "";
        if (!escapeJson) {
            return safe;
        }
        // Jackson writes the value as a quoted JSON string; the quotes are
        // the template's, not ours, so they are stripped back off.
        String quoted = Json.write(safe);
        return quoted.length() >= 2 ? quoted.substring(1, quoted.length() - 1) : safe;
    }

    // ========== config plumbing ==========

    /**
     * Resolves the HTTP verb, defaulting to POST and rejecting anything
     * outside {@link #ALLOWED_METHODS} — a typo'd verb must fail loudly
     * rather than be silently downgraded to POST, since the receiving system
     * may treat PUT and POST very differently.
     */
    private static String method(String actionName, String raw) throws Exception {
        if (raw == null) {
            return "POST";
        }
        String method = raw.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            throw new Exception("Webhook action '" + actionName + "' has unsupported method '" + raw
                    + "' (expected POST, PUT or PATCH)");
        }
        return method;
    }

    /**
     * Resolves the wall clock, clamping rather than rejecting: a config with
     * an out-of-range timeout should still deliver, and the bounds exist to
     * protect the dispatch pool, not to police the operator's number.
     */
    private static int timeoutSeconds(JsonNode config) {
        int configured = config.path("timeoutSeconds").asInt(DEFAULT_TIMEOUT_SECONDS);
        if (configured < MIN_TIMEOUT_SECONDS) {
            return MIN_TIMEOUT_SECONDS;
        }
        return Math.min(configured, MAX_TIMEOUT_SECONDS);
    }

    /**
     * The target as it may safely appear in a failure message:
     * {@code scheme://host[:port]}, never the path. See the class Javadoc —
     * for a Slack-style webhook the path <em>is</em> the credential, and
     * dispatch-log rows are readable at View Monitoring.
     */
    private static String target(URI uri) {
        String port = uri.getPort() >= 0 ? ":" + uri.getPort() : "";
        return uri.getScheme() + "://" + uri.getHost() + port;
    }

    /**
     * A short, flattened excerpt of the response body for the failure reason
     * — the difference between "HTTP 400" and "HTTP 400: invalid_payload",
     * which is usually the whole diagnosis. Empty string when there is
     * nothing to show, so the caller concatenates unconditionally.
     */
    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String flat = AlertSender.singleLine(body).trim();
        if (flat.length() > MAX_RESPONSE_SNIPPET) {
            flat = flat.substring(0, MAX_RESPONSE_SNIPPET) + "…";
        }
        return ": " + flat;
    }

    /**
     * Parses the action's config JSON, failing with an operator-readable
     * message instead of a bare Jackson parse error when the stored JSON is
     * missing or malformed.
     */
    private static JsonNode readConfig(Action action) throws Exception {
        String configJson = action.getConfigJson();
        if (configJson == null || configJson.isBlank()) {
            throw new Exception("Webhook action '" + action.getName() + "' has no configuration");
        }
        try {
            return Json.mapper().readTree(configJson);
        } catch (Exception e) {
            throw new Exception("Webhook action '" + action.getName() + "' has invalid config JSON: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Reads a trimmed text field from the config, normalizing
     * missing/null/blank to {@code null} so callers have one absence check
     * instead of three.
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * Collects at most {@link #MAX_RESPONSE_BYTES} of the response body and
     * cancels the exchange the moment the cap is reached.
     *
     * <p>Written by hand because none of the stock {@code BodySubscribers}
     * bound what they accumulate: {@code ofString}/{@code ofByteArray} buffer
     * the whole body, and {@code ofInputStream} moves the read outside the
     * request's timeout entirely, so a slow-drip body would pin a dispatch
     * thread. Cancelling on the cap also stops the far end from being able to
     * spend the sender's memory: the exchange is torn down at the first byte
     * past the limit rather than after the last one.</p>
     */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<String> {

        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<String> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                int room = MAX_RESPONSE_BYTES - buffer.size();
                if (room > 0) {
                    byte[] chunk = new byte[Math.min(room, item.remaining())];
                    item.get(chunk);
                    buffer.write(chunk, 0, chunk.length);
                }
            }
            if (buffer.size() >= MAX_RESPONSE_BYTES) {
                // Complete first, then cancel: cancellation may deliver
                // onError, and the future is already settled by then so the
                // truncated body still reaches the caller.
                complete();
                subscription.cancel();
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            complete();
        }

        /** Idempotent — {@code complete} on an already-settled future is a no-op. */
        private void complete() {
            result.complete(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        }
    }
}
