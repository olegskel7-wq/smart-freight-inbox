package com.smartfreight.inbox.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ExpectedClientJwtValidator} — only a token whose {@code azp} matches our SPA
 * client passes (ROADMAP 4.7 pre-pentest hardening). A token issued to another realm client, or one
 * with no {@code azp}, is rejected.
 */
class ExpectedClientJwtValidatorTest {

    private static final String EXPECTED = "smartfreight-spa";
    private final ExpectedClientJwtValidator validator = new ExpectedClientJwtValidator(EXPECTED);

    private Jwt jwtWithAzp(String azp) {
        Jwt.Builder b = Jwt.withTokenValue("token").header("alg", "none").subject("op@x.com");
        if (azp != null) {
            b.claim("azp", azp);
        }
        return b.build();
    }

    @Test
    void acceptsTokenFromExpectedClient() {
        OAuth2TokenValidatorResult result = validator.validate(jwtWithAzp(EXPECTED));

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenFromAnotherRealmClient() {
        OAuth2TokenValidatorResult result = validator.validate(jwtWithAzp("smartfreight-admin"));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void rejectsTokenWithNoAzpClaim() {
        OAuth2TokenValidatorResult result = validator.validate(jwtWithAzp(null));

        assertThat(result.hasErrors()).isTrue();
    }
}
