/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * The egress policy for {@link WebhookAlertSender}: decides whether a
 * configured webhook URL may be requested at all, and — the part that
 * actually matters — whether the addresses that URL <em>resolves to</em> are
 * outside the ranges an alert transport must never reach.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Every other Sentinel transport sends to a destination the engine already
 * trusts: the operator's own SMTP server, an AWS topic, a local channel. The
 * webhook transport is the first one where a privileged user types an
 * arbitrary URL and the server dutifully connects to it. That is a
 * server-side request forgery primitive by construction, and on any cloud
 * instance it has an obvious high-value target: the instance metadata service
 * at {@code 169.254.169.254}, which hands out the node's IAM role
 * credentials to anything on the box that asks, with no authentication
 * beyond being able to make the request. AWS IMDSv2, GCP and Azure all live
 * at that same link-local address; ECS task credentials live at
 * {@code 169.254.170.2}.</p>
 *
 * <p>Creating an action requires Manage Monitoring, which is a privileged
 * permission — but "privileged" is not "may exfiltrate the node's cloud
 * credentials to an arbitrary internet host". The permission is meant to
 * grant control over alerting, not a general-purpose request proxy running
 * with the engine's network identity. A monitoring-configuration permission
 * that silently escalates to the instance role is exactly the kind of quiet
 * privilege jump an audit finds later, so the transport refuses these targets
 * outright rather than relying on the permission boundary.</p>
 *
 * <h2>Why the check is on resolved addresses, not on the URL text</h2>
 *
 * <p>A string check on the URL is worthless here. {@code metadata.example.com}
 * is a perfectly ordinary hostname whose A record the attacker controls and
 * can point at {@code 169.254.169.254}; so is any of the public wildcard-DNS
 * services that resolve {@code 169.254.169.254.nip.io} to exactly that
 * address. Nothing about the URL text distinguishes those from a real
 * endpoint. The only check with any substance is: resolve the host, look at
 * <em>every</em> address that comes back, and refuse if any of them is in a
 * blocked range. That is {@link #assertTargetAllowed}, and it runs on the
 * dispatch thread immediately before the request — not at save time, where a
 * record could simply be changed afterwards.</p>
 *
 * <p><b>Every</b> address, not the first: {@code InetAddress.getAllByName}
 * returns the full RRset, and a host with one public A record and one
 * link-local A record would otherwise pass or fail depending on resolver
 * ordering. Blocking if any address is bad is the only stable rule, and it
 * costs nothing legitimate — a real webhook endpoint does not also advertise
 * a loopback address.</p>
 *
 * <h2>The residual gap, stated honestly</h2>
 *
 * <p>This is a resolve-then-connect check, and the JDK's
 * {@code java.net.http.HttpClient} offers no way to pin a request to
 * already-resolved addresses: it resolves the host again itself when it opens
 * the connection. Between our lookup and its lookup there is a window in
 * which a hostile authoritative server with a near-zero record TTL could
 * answer our query with a public address and its query with
 * {@code 169.254.169.254} — classic DNS rebinding. This code does <em>not</em>
 * close that window, and no amount of extra validation in this class would;
 * closing it properly needs an HTTP client with a pluggable DNS resolver so
 * that "the addresses we validated" and "the addresses it dials" are the same
 * object (Apache HttpClient's {@code DnsResolver} hook can do this).</p>
 *
 * <p>What narrows it to the point of being an acceptable residual risk:</p>
 * <ul>
 *   <li><b>Redirects are not followed at all</b> (see
 *       {@link WebhookAlertSender}). Redirect-chasing is the easy version of
 *       this attack — no DNS timing needed, just a 302 to
 *       {@code http://169.254.169.254/} — and refusing to follow removes it
 *       completely rather than re-validating each hop.</li>
 *   <li>The whole exchange is bounded by a short wall-clock timeout, so the
 *       attacker's usable window is seconds, and the JVM's own DNS cache
 *       (positive lookups are cached, and OIE runs under a security-manager
 *       -less default of 30 seconds) works against a per-query flip.</li>
 *   <li>Even a successful rebind yields a request the attacker cannot read
 *       the response to: the response body never leaves the server. It is
 *       recorded — truncated — only in the dispatch log's failure reason on a
 *       non-2xx status, which is readable by monitoring users. That is a real
 *       (narrow) exfiltration channel, which is why {@link WebhookAlertSender}
 *       caps that snippet hard.</li>
 * </ul>
 *
 * <p>Static utility (private constructor) per the plugin's house style.</p>
 */
public final class WebhookTargetGuard {

    /**
     * Carrier-grade NAT space (RFC 6598). Treated as private because that is
     * what it is used for — cloud provider internal fabrics and ISP-side
     * infrastructure — and because {@link InetAddress} has no predicate for
     * it, unlike the RFC 1918 ranges {@code isSiteLocalAddress} covers.
     */
    private static final int CGNAT_FIRST_OCTET = 100;
    private static final int CGNAT_SECOND_OCTET_LOW = 64;
    private static final int CGNAT_SECOND_OCTET_HIGH = 127;

    /** First byte mask/value for IPv6 unique-local addresses, {@code fc00::/7}. */
    private static final int IPV6_ULA_MASK = 0xFE;
    private static final int IPV6_ULA_VALUE = 0xFC;

    private WebhookTargetGuard() {
    }

    /**
     * Validates the URL's shape and scheme — the cheap checks that need no
     * network and are therefore safe to run on the REST thread at save time
     * as well as on the dispatch thread at send time.
     *
     * <p>HTTPS is required unless the action explicitly opts into plaintext.
     * The opt-in exists because internal-only targets on a trusted segment
     * are a legitimate case (a self-hosted Alertmanager, a lab appliance with
     * no certificate), but it is per-action and defaults off: a webhook body
     * carries the full alert narrative and its headers carry a bearer token,
     * so plaintext must be a decision someone made, not a decision someone
     * inherited from a default.</p>
     *
     * @param actionName   the action's name, for the operator-facing message
     * @param rawUrl       the configured URL
     * @param allowInsecure whether the action opted into plaintext {@code http}
     * @return the parsed URI, guaranteed absolute with an http/https scheme
     *         and a non-blank host
     * @throws IllegalArgumentException if the URL is missing, unparsable, not
     *                                  absolute, hostless, of an unsupported
     *                                  scheme, or plaintext without the opt-in
     */
    public static URI validateUrl(String actionName, String rawUrl, boolean allowInsecure) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Webhook action '" + actionName + "' has no 'url' configured");
        }
        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Webhook action '" + actionName + "' has an invalid url: "
                    + e.getReason());
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Webhook action '" + actionName
                    + "' url must be absolute and include a host, e.g. https://hooks.example.org/alerts");
        }
        if (uri.getUserInfo() != null) {
            // java.net.http drops userinfo rather than turning it into Basic
            // auth, so a credential embedded here would silently not be sent.
            // It is also the classic way to make a URL *look* like it points
            // somewhere it does not — "https://hooks.slack.com@169.254.169.254/".
            // assertTargetAllowed sees through that, but an operator reading
            // the field back should not have to.
            throw new IllegalArgumentException("Webhook action '" + actionName
                    + "' url must not embed credentials before the host; use an Authorization "
                    + "header instead");
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) {
            return uri;
        }
        if ("http".equals(scheme)) {
            if (!allowInsecure) {
                throw new IllegalArgumentException("Webhook action '" + actionName
                        + "' url must use https; enable 'Allow plaintext http' on the action to send "
                        + "alert content and credentials unencrypted");
            }
            return uri;
        }
        throw new IllegalArgumentException("Webhook action '" + actionName + "' url scheme '" + scheme
                + "' is not supported (only https, or http with the plaintext opt-in)");
    }

    /**
     * The real check: resolves the URL's host and refuses the request if any
     * resolved address is in a blocked range. Call this immediately before
     * connecting — see the class Javadoc for why the answer is only as fresh
     * as the moment it is asked.
     *
     * <p>Always blocked, regardless of {@code allowPrivateNetwork}:</p>
     * <ul>
     *   <li><b>Link-local</b> — IPv4 {@code 169.254.0.0/16} (the cloud
     *       metadata service and ECS task credentials) and IPv6
     *       {@code fe80::/10}. This is the range the whole class is about.</li>
     *   <li><b>Loopback</b> — {@code 127.0.0.0/8}, {@code ::1}. The engine's
     *       own admin API, its database, and every other unauthenticated
     *       localhost service live here.</li>
     *   <li><b>Wildcard/any-local</b> — {@code 0.0.0.0}, {@code ::}, which
     *       most stacks route to loopback.</li>
     *   <li><b>Multicast</b> — no legitimate webhook endpoint is a multicast
     *       group, and a request to one is a fan-out to unknown listeners.</li>
     * </ul>
     *
     * <p>Blocked unless {@code allowPrivateNetwork} is set: RFC 1918
     * ({@code 10/8}, {@code 172.16/12}, {@code 192.168/16}), CGNAT
     * ({@code 100.64/10}) and IPv6 unique-local ({@code fc00::/7}). These are
     * default-deny rather than always-deny because an on-premise OIE
     * legitimately posts to an internal endpoint on the same private network,
     * which is a far more common deployment than a cloud one. Opting in
     * widens the blast radius to the LAN — every internal admin panel, every
     * unauthenticated internal API — so the editor says so plainly and the
     * always-blocked ranges above are deliberately not part of the opt-in.
     * There is no configuration that permits {@code 169.254.169.254}.</p>
     *
     * @param actionName          the action's name, for the failure message
     * @param uri                 the validated URI from {@link #validateUrl}
     * @param allowPrivateNetwork whether the action opted into private/RFC 1918 targets
     * @throws Exception if the host cannot be resolved, resolves to nothing,
     *                   or resolves to any blocked address
     */
    public static void assertTargetAllowed(String actionName, URI uri, boolean allowPrivateNetwork)
            throws Exception {
        String host = uri.getHost();
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new Exception("Webhook action '" + actionName + "' cannot resolve host '" + host + "'");
        }
        if (addresses == null || addresses.length == 0) {
            throw new Exception("Webhook action '" + actionName + "' resolved host '" + host
                    + "' to no addresses");
        }
        for (InetAddress address : addresses) {
            String reason = blockReason(address, allowPrivateNetwork);
            if (reason != null) {
                // Naming the address is intentional: the operator needs to
                // know WHICH record tripped the guard to fix their DNS, and
                // they already had to hold Manage Monitoring to get here.
                throw new Exception("Webhook action '" + actionName + "' refused: host '" + host
                        + "' resolves to " + address.getHostAddress() + ", which is " + reason
                        + ". Sentinel does not allow alert delivery to that range because it would make "
                        + "an alert action a path to internal services and cloud instance credentials.");
            }
        }
    }

    /**
     * Whether a host is an IP literal rather than a name — i.e. whether
     * {@link #assertTargetAllowed} would answer without touching DNS. The
     * save path uses this to give immediate feedback on a pasted
     * {@code http://169.254.169.254/} without ever making a lookup on the
     * REST request thread (a hostile or merely slow resolver must not be able
     * to hold an admin API worker).
     *
     * <p>Deliberately a syntactic test with no name resolution of its own:
     * bracketed IPv6 literals arrive from {@link URI#getHost()} with the
     * brackets attached, dotted-quad IPv4 is a digits-and-dots string, and
     * anything else is a name.</p>
     *
     * @param host the host component of a validated URI
     * @return {@code true} when the host is an IPv4 or IPv6 literal
     */
    public static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (host.charAt(0) == '[') {
            return true;   // URI keeps the brackets on an IPv6 literal
        }
        if (host.indexOf(':') >= 0) {
            return true;   // bare IPv6 literal
        }
        // IPv4 literal: nothing but digits and dots. A hostname cannot match
        // this (a DNS label may not be all-numeric in any usable TLD), and if
        // one somehow did, getAllByName resolves it anyway at send time.
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if ((c < '0' || c > '9') && c != '.') {
                return false;
            }
        }
        return true;
    }

    /**
     * Save-time convenience: applies the address policy only when the host is
     * an IP literal, so an obviously-bad target is rejected in the editor
     * instead of silently failing on the first real alert, while a hostname
     * costs no DNS lookup on the REST thread. A no-op for hostnames — the
     * authoritative check is always {@link #assertTargetAllowed} at send time.
     *
     * @param actionName          the action's name, for the failure message
     * @param uri                 the validated URI from {@link #validateUrl}
     * @param allowPrivateNetwork whether the action opted into private targets
     * @throws IllegalArgumentException if the literal address is blocked (a
     *                                  400, since this runs during validation)
     */
    public static void assertLiteralTargetAllowed(String actionName, URI uri, boolean allowPrivateNetwork) {
        if (!isIpLiteral(uri.getHost())) {
            return;
        }
        try {
            assertTargetAllowed(actionName, uri, allowPrivateNetwork);
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    /**
     * Classifies one resolved address against the policy.
     *
     * @return {@code null} when the address is permitted, otherwise a short
     *         noun phrase naming the range, used verbatim in the failure
     *         message ("…which is a link-local address …")
     */
    private static String blockReason(InetAddress address, boolean allowPrivateNetwork) {
        if (address.isLinkLocalAddress()) {
            // 169.254/16 and fe80::/10. The cloud metadata service
            // (169.254.169.254) and ECS task credentials (169.254.170.2) are
            // both in here; this branch is the reason the class exists.
            return "a link-local address (169.254.0.0/16 and fe80::/10 — the range the cloud "
                    + "instance metadata service lives in)";
        }
        if (address.isLoopbackAddress()) {
            return "a loopback address";
        }
        if (address.isAnyLocalAddress()) {
            return "the wildcard address";
        }
        if (address.isMulticastAddress()) {
            return "a multicast address";
        }
        if (allowPrivateNetwork) {
            return null;
        }
        if (address.isSiteLocalAddress()) {
            // 10/8, 172.16/12, 192.168/16 for IPv4; the deprecated fec0::/10
            // for IPv6.
            return "a private (RFC 1918) address and this action does not allow private targets";
        }
        byte[] octets = address.getAddress();
        if (octets.length == 4) {
            int first = octets[0] & 0xFF;
            int second = octets[1] & 0xFF;
            if (first == CGNAT_FIRST_OCTET
                    && second >= CGNAT_SECOND_OCTET_LOW && second <= CGNAT_SECOND_OCTET_HIGH) {
                return "a carrier-grade NAT address (100.64/10) and this action does not allow "
                        + "private targets";
            }
        } else if (octets.length == 16 && (octets[0] & IPV6_ULA_MASK) == IPV6_ULA_VALUE) {
            return "an IPv6 unique-local address (fc00::/7) and this action does not allow "
                    + "private targets";
        }
        return null;
    }
}
