package org.openintegrationengine.plugins.sentinel.server.service;

/**
 * Versioned encryption for webhook fields previously stored as plaintext.
 * Existing rows remain deliverable; saving one upgrades retained plaintext
 * fields. A failed decrypt never falls back to treating ciphertext as a URL,
 * template, or header. Existing encrypted header slots keep their original format.
 */
public final class WebhookSecrets {
    private static final String PREFIX = "sentinel-secret:v1:";

    private WebhookSecrets() { }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public static String encrypt(String plaintext) {
        String ciphertext = SettingsCrypto.encrypt(plaintext);
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new IllegalStateException("Webhook secret encryption produced no value");
        }
        return PREFIX + ciphertext;
    }

    public static String read(String stored) {
        if (!isEncrypted(stored)) {
            return stored; // Legacy plaintext URL/template, redacted on every read.
        }
        try {
            String plaintext = SettingsCrypto.decrypt(stored.substring(PREFIX.length()));
            if (plaintext == null || plaintext.isBlank()) {
                throw new IllegalStateException("Empty decrypted webhook secret");
            }
            return plaintext;
        } catch (RuntimeException e) {
            // Encryptor exception text may contain ciphertext or input bytes.
            throw new IllegalStateException("Stored webhook secret could not be decrypted; re-enter it");
        }
    }
}
