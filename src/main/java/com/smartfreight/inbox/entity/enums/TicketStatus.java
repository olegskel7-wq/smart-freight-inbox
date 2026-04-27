package com.smartfreight.inbox.entity.enums;

/**
 * Bilieto gyvavimo ciklo būsenų sąrašas.
 *
 * Perėjimų schema:
 *   NEW → PROCESSING → DRAFT_READY → CLOSED
 *                    ↘ FAILED
 *                    ↘ RETRY_LATER → NEW (per RetryScheduler)
 */
public enum TicketStatus {

    /** Naujas laiškas gautas, dar neapdorotas. */
    NEW,

    /** Laiškas šiuo metu apdorojamas (AI ištraukia konteinerį, ieško DB, generuoja atsakymą). */
    PROCESSING,

    /** Juodraštis paruoštas – operatorius gali peržiūrėti ir išsiųsti. */
    DRAFT_READY,

    /** Apdorojimas nepavyko (AI nepasiekiamas, analizės klaida ir pan.). Operatorius turi spręsti rankiniu būdu. */
    FAILED,

    /** Laikina klaida – suplanuotas pakartotinis bandymas. RetryScheduler automatiškai paims biletą vėliau. */
    RETRY_LATER,

    /** Bilietas uždarytas operatoriaus – atsakymas išsiųstas arba klausimas išspręstas be atsakymo. */
    CLOSED
}
