package com.smartfreight.inbox.util;

import jakarta.mail.internet.InternetAddress;

/**
 * Utilities for parsing email address strings.
 *
 * Centralizes the RFC 5321 / "Name &lt;address&gt;" extraction logic
 * that was previously duplicated across reply / draft / notification services.
 */
public final class EmailAddressUtils {

    private EmailAddressUtils() {}

    /**
     * Extracts the clean address part from an RFC 5321 string.
     * <ul>
     *   <li>{@code "john@example.com"} → {@code "john@example.com"}</li>
     *   <li>{@code "John Doe <john@example.com>"} → {@code "john@example.com"}</li>
     *   <li>{@code null} or blank → empty string</li>
     *   <li>unparseable → raw input unchanged (fallback)</li>
     * </ul>
     *
     * @param raw RFC 5321 formatted string or a plain email address
     * @return clean email address, or empty string if input is null/blank
     */
    public static String extract(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            return new InternetAddress(raw).getAddress();
        } catch (Exception e) {
            return raw;
        }
    }
}
