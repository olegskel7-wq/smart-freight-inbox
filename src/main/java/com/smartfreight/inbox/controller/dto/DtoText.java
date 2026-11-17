package com.smartfreight.inbox.controller.dto;

import java.util.regex.Pattern;

/** Text trimming shared by the list DTOs, which ship previews of TEXT columns, never whole blobs. */
final class DtoText {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private DtoText() {
    }

    /** Trims to at most {@code max} characters, appending an ellipsis when the text was cut. */
    static String abbreviate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1) + "…";
    }

    /**
     * One-line preview: whitespace runs collapsed, then abbreviated to {@code max}; null for a blank
     * text. Only a bounded head is collapsed — bodies can be megabytes and only a line is shown.
     */
    static String oneLine(String text, int max) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String head = text.length() > max * 4 ? text.substring(0, max * 4) : text;
        return abbreviate(WHITESPACE.matcher(head.strip()).replaceAll(" "), max);
    }
}
