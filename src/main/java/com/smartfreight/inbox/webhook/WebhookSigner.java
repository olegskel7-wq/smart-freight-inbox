package com.smartfreight.inbox.webhook;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Signs outbound webhook requests with HMAC-SHA256 (Phase 3.5).
 *
 * <p>The receiver verifies authenticity and integrity by recomputing the signature with the shared
 * secret. The signed string is {@code "<timestamp>.<body>"} (the Stripe/GitHub scheme): including the
 * timestamp lets the receiver reject replays of an old captured request. The signature travels in the
 * {@value #SIGNATURE_HEADER} header as {@code t=<unix-seconds>,v1=<hex>}, so the scheme can be
 * versioned later without breaking existing verifiers.
 */
@Component
public class WebhookSigner {

    public static final String SIGNATURE_HEADER = "X-SmartFreight-Signature";
    public static final String EVENT_HEADER = "X-SmartFreight-Event";
    public static final String DELIVERY_HEADER = "X-SmartFreight-Delivery";

    private static final String HMAC_ALGO = "HmacSHA256";

    /**
     * Builds the {@value #SIGNATURE_HEADER} value for a request body at a given time.
     *
     * @param secret        the subscription's cleartext signing secret
     * @param timestamp     unix epoch seconds of this attempt
     * @param body          the exact request body bytes (the JSON envelope)
     */
    public String signatureHeader(String secret, long timestamp, String body) {
        String signed = timestamp + "." + body;
        String hex = hmacHex(secret, signed);
        return "t=" + timestamp + ",v1=" + hex;
    }

    /** Lowercase hex HMAC-SHA256 of {@code data} keyed by {@code secret} — the v1 signature primitive. */
    public String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (GeneralSecurityException e) {
            // SHA-256 HMAC is always available on a standard JRE; a failure here is a JVM defect.
            throw new IllegalStateException("Failed to compute webhook signature", e);
        }
    }
}
