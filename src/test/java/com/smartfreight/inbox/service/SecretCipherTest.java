package com.smartfreight.inbox.service;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-JCA unit tests for {@link SecretCipher} — no Spring context needed.
 */
class SecretCipherTest {

    private static String randomKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    @Test
    void roundTripsArbitraryText() {
        SecretCipher cipher = new SecretCipher(randomKey());

        String secret = "p@ssw0rd с кириллицей и émoji 🚚";
        String stored = cipher.encrypt(secret);

        assertThat(stored).isNotEqualTo(secret);
        assertThat(cipher.decrypt(stored)).isEqualTo(secret);
    }

    @Test
    void everyEncryptionProducesDifferentCiphertext() {
        SecretCipher cipher = new SecretCipher(randomKey());

        // Random IV per call — identical plaintexts must not produce identical rows,
        // or the DB would leak which tenants share a password.
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void tamperedCiphertextFailsAuthentication() {
        SecretCipher cipher = new SecretCipher(randomKey());
        byte[] blob = Base64.getDecoder().decode(cipher.encrypt("secret"));
        blob[blob.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(blob);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decrypt");
    }

    @Test
    void wrongKeyFailsAuthentication() {
        String stored = new SecretCipher(randomKey()).encrypt("secret");

        assertThatThrownBy(() -> new SecretCipher(randomKey()).decrypt(stored))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingKeyFailsAtUseWithClearMessage() {
        SecretCipher cipher = new SecretCipher("");

        assertThat(cipher.isConfigured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAILBOX_ENCRYPTION_KEY");
    }

    @Test
    void invalidKeyLengthRejectedAtConstruction() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new SecretCipher(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
