package com.smartfreight.inbox.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts secrets at rest with AES-256-GCM (Phase 2.2 S3).
 *
 * <p>Built for {@code tenant_mailbox.password_enc}: client IMAP passwords must not sit in
 * plaintext in the DB — the daily {@code pg_dump} backup volume would otherwise contain
 * every customer's mailbox credentials, which is indefensible for a product positioned
 * on UK data protection.
 *
 * <p>The key comes from the {@code MAILBOX_ENCRYPTION_KEY} environment variable (base64,
 * exactly 32 bytes). Key custody is environment-level by design: a DB dump alone is
 * useless without the deployment's env. Phase 4 (Vault) replaces the key SOURCE, not this
 * scheme. Generate a key with: {@code openssl rand -base64 32}.
 *
 * <p>Wire format: {@code base64(iv[12] || ciphertext+tag)}. A fresh random IV per
 * encryption — GCM's one hard requirement is that an (iv, key) pair is never reused.
 *
 * <p>Without the env var the application still starts (dev setups have no mailboxes to
 * encrypt); the first encrypt/decrypt call fails with a clear message instead.
 */
@Service
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${mailbox.encryption-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("MAILBOX_ENCRYPTION_KEY is not valid base64", e);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("MAILBOX_ENCRYPTION_KEY must decode to exactly "
                    + KEY_BYTES + " bytes (got " + raw.length + "). Generate one with: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** Whether a key is configured — mailbox management UI uses this to fail before the form. */
    public boolean isConfigured() {
        return key != null;
    }

    /** Encrypts a secret for storage. Output is base64 and safe for a VARCHAR column. */
    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    /** Decrypts a stored secret. Tampered or foreign-key ciphertext fails GCM authentication. */
    public String decrypt(String encoded) {
        requireKey();
        try {
            byte[] blob = Base64.getDecoder().decode(encoded);
            if (blob.length <= IV_BYTES) {
                throw new IllegalStateException("Stored secret is too short to contain an IV");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES));
            byte[] plaintext = cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Failed to decrypt secret — wrong MAILBOX_ENCRYPTION_KEY or corrupted value", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "MAILBOX_ENCRYPTION_KEY is not set — mailbox credentials cannot be "
                            + "encrypted/decrypted. Set it to a base64-encoded 32-byte key "
                            + "(openssl rand -base64 32).");
        }
    }
}
