package com.smartfreight.inbox.config;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects bearer tokens that were not issued to this platform's own SPA client (ROADMAP 4.7
 * pre-pentest hardening).
 *
 * <p>The resource server validates a token's issuer + signature, but those alone accept ANY valid
 * token minted by the realm — including one issued to a different client in the same realm. We want
 * only tokens obtained by our React SPA. Keycloak stamps the requesting client into the {@code azp}
 * (authorized party) claim on every access token, so we pin it here.
 *
 * <p>Why {@code azp} and not {@code aud}: the {@code smartfreight-spa} client has no audience mapper
 * in the realm, so its access tokens carry no {@code aud} for itself — validating {@code aud} would
 * need a realm change + re-import. {@code azp} is always present and needs no realm change.
 *
 * <p>Fail-closed: a missing or mismatched {@code azp} fails validation.
 */
public class ExpectedClientJwtValidator implements OAuth2TokenValidator<Jwt> {

    /** Keycloak access-token claim naming the client that obtained the token. */
    static final String AZP_CLAIM = "azp";

    private final String expectedClient;
    private final OAuth2Error error;

    public ExpectedClientJwtValidator(String expectedClient) {
        this.expectedClient = expectedClient;
        this.error = new OAuth2Error("invalid_token",
                "Token authorized party (azp) is not the expected client '" + expectedClient + "'",
                null);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String azp = token.getClaimAsString(AZP_CLAIM);
        if (expectedClient.equals(azp)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(error);
    }
}
