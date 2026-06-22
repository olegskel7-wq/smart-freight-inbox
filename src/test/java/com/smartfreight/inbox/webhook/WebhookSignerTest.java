package com.smartfreight.inbox.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the HMAC-SHA256 webhook signer (Phase 3.5). Verifies the header format and that the
 * signature is deterministic for a given (secret, timestamp, body) and changes when any of them does —
 * the properties a receiver relies on to verify authenticity and reject replays.
 */
class WebhookSignerTest {

    private final WebhookSigner signer = new WebhookSigner();

    @Test
    void signatureHeaderHasTimestampAndV1() {
        String header = signer.signatureHeader("whsec_test", 1_700_000_000L, "{\"a\":1}");

        assertThat(header).startsWith("t=1700000000,v1=");
        // v1 is a 64-char lowercase hex SHA-256 digest.
        String hex = header.substring(header.indexOf("v1=") + 3);
        assertThat(hex).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void sameInputsProduceSameSignature() {
        String a = signer.signatureHeader("secret", 100L, "body");
        String b = signer.signatureHeader("secret", 100L, "body");

        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentSecretBodyOrTimestampChangesSignature() {
        String base = signer.hmacHex("secret", "100.body");

        assertThat(signer.hmacHex("other", "100.body")).isNotEqualTo(base);
        assertThat(signer.hmacHex("secret", "101.body")).isNotEqualTo(base);
        assertThat(signer.hmacHex("secret", "100.body2")).isNotEqualTo(base);
    }

    @Test
    void hmacMatchesKnownVector() {
        // RFC-style sanity check: HMAC-SHA256(key="key", data="The quick brown fox jumps over the lazy dog").
        String hex = signer.hmacHex("key", "The quick brown fox jumps over the lazy dog");

        assertThat(hex).isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }
}
