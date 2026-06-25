package com.smartfreight.inbox.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link UrlSafetyValidator} (Phase 3.5) — the SSRF gate on webhook URLs. A public HTTPS
 * URL passes; non-HTTPS, embedded credentials, and any URL resolving to a loopback / private / link-local
 * address (the cloud metadata endpoint) are rejected.
 */
class UrlSafetyValidatorTest {

    private final UrlSafetyValidator validator = new UrlSafetyValidator();

    @Test
    void acceptsPublicHttpsUrl() {
        assertThatCode(() -> validator.validate("https://hooks.zapier.com/hooks/catch/123/abc"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsHttp() {
        assertThatThrownBy(() -> validator.validate("http://example.com/hook"))
                .isInstanceOf(UrlSafetyValidator.UnsafeUrlException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsCredentialsInUrl() {
        assertThatThrownBy(() -> validator.validate("https://user:pass@example.com/hook"))
                .isInstanceOf(UrlSafetyValidator.UnsafeUrlException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    void rejectsLoopback() {
        assertThatThrownBy(() -> validator.validate("https://127.0.0.1/hook"))
                .isInstanceOf(UrlSafetyValidator.UnsafeUrlException.class);
    }

    @Test
    void rejectsPrivateRange() {
        assertThatThrownBy(() -> validator.validate("https://10.0.0.5/hook"))
                .isInstanceOf(UrlSafetyValidator.UnsafeUrlException.class);
    }

    @Test
    void rejectsCloudMetadataAddress() {
        // 169.254.169.254 is link-local — the AWS/GCP metadata endpoint, the classic SSRF target.
        assertThatThrownBy(() -> validator.validate("https://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(UrlSafetyValidator.UnsafeUrlException.class);
    }

    @Test
    void rejectsIpv6UniqueLocal() {
        // fc00::/7 (RFC 4193) — internal IPv6, NOT covered by isSiteLocalAddress (deprecated fec0::/10).
        assertThatThrownBy(() -> validator.validate("https://[fd00::1]/hook"))
                .isInstanceOf(UrlSafetyValidator.UnsafeUrlException.class)
                .hasMessageContaining("non-public address");
    }

    @Test
    void rejectsCarrierGradeNat() {
        // 100.64.0.0/10 (RFC 6598) — CGNAT shared space, not flagged by any InetAddress predicate.
        assertThatThrownBy(() -> validator.validate("https://100.64.0.1/hook"))
                .isInstanceOf(UrlSafetyValidator.UnsafeUrlException.class)
                .hasMessageContaining("non-public address");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> validator.validate("not a url"))
                .isInstanceOf(UrlSafetyValidator.UnsafeUrlException.class);
    }
}
