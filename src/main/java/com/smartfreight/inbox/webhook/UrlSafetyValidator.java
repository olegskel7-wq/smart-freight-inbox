package com.smartfreight.inbox.webhook;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Validates a webhook destination URL before it is saved (Phase 3.5).
 *
 * <p>In a multi-tenant platform a webhook URL is attacker-controlled input: without checks a tenant
 * could point one at internal infrastructure (the database, the metadata endpoint, another tenant's
 * service) and use the dispatcher as an SSRF proxy. This rejects anything that is not plain HTTPS to a
 * public host: no {@code http}, no credentials in the URL, no loopback / link-local / private / wildcard
 * addresses (including the cloud metadata address {@code 169.254.169.254}).
 *
 * <p>This is a save-time gate. A DNS name that resolves to a public address now but a private one later
 * (DNS rebinding) is a residual risk; a re-check at delivery time is noted as later hardening.
 */
@Component
public class UrlSafetyValidator {

    /** Thrown when a URL is rejected; the message is surfaced to the operator as a 400. */
    public static class UnsafeUrlException extends RuntimeException {
        public UnsafeUrlException(String message) {
            super(message);
        }
    }

    /** Validates {@code url} or throws {@link UnsafeUrlException} with a human-readable reason. */
    public void validate(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new UnsafeUrlException("Not a valid URL.");
        }

        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) {
            throw new UnsafeUrlException("Webhook URL must use HTTPS.");
        }
        if (uri.getUserInfo() != null) {
            throw new UnsafeUrlException("Webhook URL must not contain credentials.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new UnsafeUrlException("Webhook URL must have a host.");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new UnsafeUrlException("Webhook host could not be resolved.");
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new UnsafeUrlException(
                        "Webhook URL resolves to a non-public address and is not allowed.");
            }
        }
    }

    /** True for any address the dispatcher must never connect to (internal / non-routable ranges). */
    private boolean isBlocked(InetAddress address) {
        return address.isLoopbackAddress()      // 127.0.0.0/8, ::1
                || address.isAnyLocalAddress()   // 0.0.0.0, ::
                || address.isLinkLocalAddress()  // 169.254.0.0/16 (incl. cloud metadata), fe80::/10
                || address.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || address.isMulticastAddress();
    }
}
