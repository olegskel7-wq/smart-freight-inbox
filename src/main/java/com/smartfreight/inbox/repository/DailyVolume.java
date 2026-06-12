package com.smartfreight.inbox.repository;

import java.time.LocalDate;

/**
 * Native-query projection: ticket intake for one calendar day (Phase 2.1 volume chart).
 *
 * Backed by {@link EmailTicketRepository#dailyVolumeSince} — Spring Data maps the aliased
 * columns onto the getters and converts {@code java.sql.Date} to {@link LocalDate}.
 * Days with no tickets are absent from the result; AnalyticsService fills the gaps so the
 * chart axis stays continuous.
 */
public interface DailyVolume {

    LocalDate getDay();

    /** Every email persisted that day, junk included. */
    long getTotal();

    /** The IRRELEVANT share of {@link #getTotal()}. */
    long getJunk();
}
