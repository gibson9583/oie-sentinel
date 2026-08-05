/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.service;

import com.mirth.commons.encryption.EncryptionException;
import com.mirth.commons.encryption.Encryptor;
import com.mirth.connect.server.controllers.ConfigurationController;

/**
 * Encrypts and decrypts Sentinel's stored secrets (today: the SNS action's
 * {@code secretAccessKey}) with the engine's own configured
 * {@link Encryptor}.
 *
 * <p>Using the engine's encryptor rather than a Sentinel-owned key means the
 * secret is protected by the same keystore the engine already protects its
 * own credentials with — no second key to provision, rotate, or lose, and a
 * database dump alone can never yield the plaintext. This is a deliberate
 * improvement over the sqs-source-connector precedent, which stores AWS
 * secrets in plaintext.</p>
 *
 * <p>Note the import: {@code com.mirth.commons.encryption.Encryptor} is the
 * engine's real crypto interface — the similarly named donkey
 * {@code Encryptor} is an unrelated interface and must not be used.</p>
 */
public final class SettingsCrypto {

    private SettingsCrypto() {
    }

    /**
     * Encrypts a plaintext secret for storage.
     *
     * @param plaintext the secret to encrypt; {@code null}/blank returns
     *                  {@code null} so callers can pass optional config
     *                  fields straight through without pre-checking
     * @return the encrypted form, or {@code null} for null/blank input
     * @throws RuntimeException if the engine's encryptor fails — a
     *                          hard configuration problem (missing keystore)
     *                          that must surface, never be stored as if it
     *                          were an encrypted value
     */
    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        try {
            return encryptor().encrypt(plaintext);
        } catch (EncryptionException e) {
            throw new RuntimeException("Failed to encrypt Sentinel secret with the engine encryptor", e);
        }
    }

    /**
     * Decrypts a stored secret back to plaintext, e.g. just-in-time inside
     * the SNS sender so the plaintext never rests anywhere but the AWS SDK
     * call.
     *
     * @param ciphertext the stored encrypted value; {@code null}/blank
     *                   returns {@code null} (an action with no stored
     *                   secret simply has no secret)
     * @return the plaintext, or {@code null} for null/blank input
     * @throws RuntimeException if decryption fails — the stored value is
     *                          corrupt or was encrypted under a different
     *                          keystore, which the operator must fix by
     *                          re-entering the secret
     */
    public static String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        try {
            return encryptor().decrypt(ciphertext);
        } catch (EncryptionException e) {
            throw new RuntimeException("Failed to decrypt Sentinel secret with the engine encryptor", e);
        }
    }

    /**
     * Resolves the engine encryptor on every call rather than caching it:
     * {@code ConfigurationController.getInstance()} is itself a cached
     * singleton lookup, and resolving late avoids any dependence on plugin
     * start ordering relative to the controller's initialization.
     */
    private static Encryptor encryptor() {
        return ConfigurationController.getInstance().getEncryptor();
    }
}
