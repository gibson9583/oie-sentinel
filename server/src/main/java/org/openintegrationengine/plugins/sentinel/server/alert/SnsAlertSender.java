/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.utils.SdkAutoCloseable;

import org.openintegrationengine.plugins.sentinel.server.service.SettingsCrypto;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;

/**
 * Publishes alert notifications to an AWS SNS topic — the transport for
 * shops whose on-call fan-out (PagerDuty, OpsGenie, SMS, Lambda) is already
 * subscribed to SNS.
 *
 * <p>The credentials handling deliberately mirrors the sqs-source-connector
 * plugin (its {@code AwsConnectorCredentials} class) so the two OIE AWS
 * integrations behave identically for operators: DEFAULT uses the SDK's
 * provider chain (env vars, instance profile, IRSA), STATIC uses an explicit
 * key pair, ROLE assumes an IAM role via STS with an optional external id.
 * Client and credentials are built per send and closed per send
 * (try-with-resources): alert traffic is low-volume, and a short-lived
 * client per publish means a changed action config or rotated credential
 * takes effect on the next alert with no cache to invalidate.</p>
 *
 * <p>Action {@code configJson} shape: {@code {authType DEFAULT|STATIC|ROLE,
 * accessKeyId?, secretAccessKey?, region, topicArn, assumeRoleArn?,
 * assumeRoleExternalId?}}. {@code secretAccessKey} is stored encrypted by
 * ActionService and decrypted here just-in-time via
 * {@link SettingsCrypto#decrypt(String)} — the plaintext secret exists only
 * inside this method's frame, never in a field or a DTO that could be
 * serialized.</p>
 */
public final class SnsAlertSender implements AlertSender {

    /**
     * SNS caps the subject at 100 characters (and rejects longer publishes
     * outright), so the summary is truncated rather than risking a failed
     * delivery over a long monitor name.
     */
    private static final int MAX_SUBJECT_LENGTH = 100;

    /**
     * Publishes one notification: subject = short payload summary, message =
     * the full {@link AlertPayload} as JSON so topic subscribers get the
     * complete machine-readable event.
     *
     * <p>SDK exceptions ({@code SnsException}, credential/STS failures)
     * propagate untouched per the {@link AlertSender} contract — the
     * dispatcher records their message as the dispatch failure reason.</p>
     */
    @Override
    public void send(Action action, AlertEvent event, AlertPayload payload) throws Exception {
        JsonNode config = readConfig(action);

        String topicArn = text(config, "topicArn");
        if (topicArn == null) {
            // Validated at save time by ActionService; defend anyway so a
            // hand-edited config fails with an actionable reason.
            throw new Exception("SNS action '" + action.getName() + "' has no topicArn configured");
        }
        String region = text(config, "region");
        AuthType authType = parseAuthType(action, text(config, "authType"));

        // Decrypted only on the STATIC path and only just before use, so the
        // plaintext secret never outlives this send.
        String secretAccessKey = authType == AuthType.STATIC
                ? SettingsCrypto.decrypt(text(config, "secretAccessKey"))
                : null;

        String subject = buildSubject(payload);
        String body = Json.write(payload);

        // Declaration order is the close-order contract: try-with-resources
        // closes in reverse, so the client closes before the credentials it
        // borrows — the same order sqs-source-connector's closeClients() uses.
        try (SnsCredentials credentials = SnsCredentials.create(authType,
                text(config, "accessKeyId"), secretAccessKey,
                text(config, "assumeRoleArn"), text(config, "assumeRoleExternalId"), region);
                SnsClient client = buildClient(credentials, region)) {
            client.publish(PublishRequest.builder()
                    .topicArn(topicArn)
                    .subject(subject)
                    .message(body)
                    .build());
        }
    }

    /**
     * Builds the SNS client, applying the region only when configured —
     * when blank, the SDK's own region chain (env var, profile, instance
     * metadata) resolves it, exactly as sqs-source-connector behaves.
     */
    private static SnsClient buildClient(SnsCredentials credentials, String region) {
        SnsClientBuilder builder = SnsClient.builder()
                .credentialsProvider(credentials.getProvider());
        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region));
        }
        return builder.build();
    }

    /**
     * Builds the subject line: severity first for at-a-glance triage, then
     * monitor/channel/status. Control characters are flattened to spaces and
     * a plain hyphen joins the names because SNS restricts subjects to
     * single-line ASCII-ish text — an em dash or embedded newline from a
     * creative monitor name must not fail the publish.
     */
    private static String buildSubject(AlertPayload payload) {
        String severity = payload.getSeverity() != null ? payload.getSeverity().name() : "UNKNOWN";
        String summary = "[Sentinel][" + severity + "] " + safe(payload.getMonitorName())
                + " - " + safe(payload.getChannelName()) + ": " + safe(payload.getEventType());
        summary = summary.replaceAll("[\\r\\n\\t\\p{Cntrl}]", " ");
        if (summary.length() > MAX_SUBJECT_LENGTH) {
            summary = summary.substring(0, MAX_SUBJECT_LENGTH);
        }
        return summary;
    }

    /**
     * Parses the configured auth type, defaulting an absent value to DEFAULT
     * (the least-configuration path) but rejecting an unrecognized one —
     * silently downgrading a typo'd "STATIC" to the instance profile would
     * publish with the wrong identity.
     */
    private static AuthType parseAuthType(Action action, String raw) throws Exception {
        if (raw == null) {
            return AuthType.DEFAULT;
        }
        try {
            return AuthType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Exception("SNS action '" + action.getName() + "' has unknown authType '" + raw
                    + "' (expected DEFAULT, STATIC or ROLE)");
        }
    }

    /**
     * Parses the action's config JSON, failing with an operator-readable
     * message instead of a bare Jackson parse error when the stored JSON is
     * missing or malformed.
     */
    private static JsonNode readConfig(Action action) throws Exception {
        String configJson = action.getConfigJson();
        if (configJson == null || configJson.isBlank()) {
            throw new Exception("SNS action '" + action.getName() + "' has no configuration");
        }
        try {
            return Json.mapper().readTree(configJson);
        } catch (Exception e) {
            throw new Exception("SNS action '" + action.getName() + "' has invalid config JSON: "
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

    /** Null-to-empty guard so the subject line never prints "null". */
    private static String safe(String value) {
        return value != null ? value : "";
    }

    /** How the sender authenticates to AWS; mirrors sqs-source-connector's enum. */
    enum AuthType {
        /** The SDK's default provider chain (env vars, instance profile, etc.). */
        DEFAULT,
        /** Explicit access key id + secret access key. */
        STATIC,
        /** Assume an IAM role via STS. */
        ROLE
    }

    /**
     * Owns the credentials provider and the closeable resources behind it —
     * a nested mirror of sqs-source-connector's {@code AwsConnectorCredentials}
     * (nested rather than a separate file because SNS alerting is its only
     * consumer in this plugin).
     *
     * <p>Why the explicit lifecycle: the AWS SDK does <em>not</em> close
     * user-supplied credential providers when a service client is closed, so
     * for ROLE auth the STS client and the assume-role provider must be
     * closed here or every alert would leak an STS connection pool.
     * {@code DefaultCredentialsProvider} is a JVM-wide singleton and must
     * never be closed, which is why {@link #close()} only acts when an STS
     * client exists.</p>
     */
    static final class SnsCredentials implements AutoCloseable {

        private final AwsCredentialsProvider provider;
        private final StsClient stsClient;

        private SnsCredentials(AwsCredentialsProvider provider, StsClient stsClient) {
            this.provider = provider;
            this.stsClient = stsClient;
        }

        /**
         * Builds the provider for the given auth settings. All values must
         * already be resolved and decrypted — this class never sees
         * ciphertext.
         *
         * @param authType        which authentication path to build
         * @param accessKeyId     STATIC only: the access key id
         * @param secretAccessKey STATIC only: the decrypted secret
         * @param roleArn         ROLE only: the role to assume
         * @param externalId      ROLE only: optional external id for the trust policy
         * @param region          used to place the STS endpoint for ROLE; blank
         *                        falls back to the SDK's region chain
         */
        static SnsCredentials create(AuthType authType, String accessKeyId, String secretAccessKey,
                String roleArn, String externalId, String region) {
            switch (authType) {
                case STATIC:
                    return new SnsCredentials(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKeyId, secretAccessKey)), null);

                case ROLE:
                    AssumeRoleRequest.Builder roleRequestBuilder = AssumeRoleRequest.builder()
                            .roleArn(roleArn)
                            .roleSessionName("oie-sentinel");

                    if (externalId != null && !externalId.isBlank()) {
                        roleRequestBuilder.externalId(externalId);
                    }

                    StsClient stsClient = region != null && !region.isBlank()
                            ? StsClient.builder().region(Region.of(region)).build()
                            : StsClient.builder().build();

                    AwsCredentialsProvider provider = StsAssumeRoleCredentialsProvider.builder()
                            .stsClient(stsClient)
                            .refreshRequest(roleRequestBuilder.build())
                            .build();

                    return new SnsCredentials(provider, stsClient);

                case DEFAULT:
                default:
                    return new SnsCredentials(DefaultCredentialsProvider.create(), null);
            }
        }

        /** @return the provider service clients should authenticate with */
        AwsCredentialsProvider getProvider() {
            return provider;
        }

        /**
         * Closes the STS client and assume-role provider for ROLE auth; a
         * no-op for DEFAULT/STATIC, which hold no closeable resources (see
         * class Javadoc for why DEFAULT must never be closed).
         */
        @Override
        public void close() {
            if (stsClient != null) {
                if (provider instanceof SdkAutoCloseable) {
                    ((SdkAutoCloseable) provider).close();
                }
                stsClient.close();
            }
        }
    }
}
