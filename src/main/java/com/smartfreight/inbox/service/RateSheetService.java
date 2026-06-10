package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.CarrierRate;
import com.smartfreight.inbox.repository.CarrierRateRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Carrier rate sheets (Phase 1.2): CSV import and rate lookup for the drafting prompt.
 *
 * <p>Expected CSV header: {@code carrier,origin,destination,rate,currency,margin_percent,valid_until}.
 * {@code currency} (default GBP), {@code margin_percent} (default 0) and {@code valid_until}
 * (ISO date, empty = no expiry) are optional per row. Rows are upserted by
 * (carrier, origin, destination), so re-uploading a sheet updates prices in place.
 *
 * <p>Lookup is deterministic: a rate matches when both its origin and destination appear as
 * whole words in the email text. AI-based route extraction is deliberately out of scope here —
 * it belongs to extended classification (Phase 1.4).
 */
@Service
public class RateSheetService {

    private static final Logger log = LoggerFactory.getLogger(RateSheetService.class);

    /** Feature flag gating rate lookup in the pipeline (seeded by migration V13). */
    public static final String FEATURE_KEY = "feature.rate-sheets";

    private static final Set<String> REQUIRED_HEADERS = Set.of("carrier", "origin", "destination", "rate");

    /** Cap on rates injected into the prompt — keeps it focused when many routes match. */
    private static final int MAX_RATES_IN_PROMPT = 5;

    /** Cap on per-row error messages returned to the UI from one import. */
    private static final int MAX_REPORTED_ERRORS = 20;

    /** English locale — the prompt is English regardless of the server's default locale. */
    private static final DateTimeFormatter VALID_UNTIL_DISPLAY =
            DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale.ENGLISH);

    private final CarrierRateRepository repository;
    private final SettingsService settingsService;

    public RateSheetService(CarrierRateRepository repository, SettingsService settingsService) {
        this.repository = repository;
        this.settingsService = settingsService;
    }

    /** Outcome of one CSV import: row counts plus per-row errors for the UI. */
    public record ImportResult(int inserted, int updated, List<String> errors) {
        public int total() {
            return inserted + updated;
        }
    }

    /**
     * Imports a rate sheet CSV, upserting each row by (carrier, origin, destination).
     * Malformed rows are skipped and reported back rather than failing the whole upload.
     *
     * @param in CSV stream (UTF-8, header row required)
     * @return counts of inserted/updated rows and per-row error messages
     * @throws IOException              on unreadable input
     * @throws IllegalArgumentException when required header columns are missing
     */
    @Transactional
    public ImportResult importCsv(InputStream in) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .setIgnoreHeaderCase(true)
                .build();

        int inserted = 0;
        int updated = 0;
        List<String> errors = new ArrayList<>();

        try (CSVParser parser = format.parse(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<String> headers = parser.getHeaderNames().stream().map(String::toLowerCase).toList();
            if (!headers.containsAll(REQUIRED_HEADERS)) {
                throw new IllegalArgumentException(
                        "CSV header must contain columns: carrier, origin, destination, rate "
                                + "(optional: currency, margin_percent, valid_until). Found: " + headers);
            }

            for (CSVRecord record : parser) {
                try {
                    boolean wasNew = upsertRow(record);
                    if (wasNew) {
                        inserted++;
                    } else {
                        updated++;
                    }
                } catch (Exception e) {
                    if (errors.size() < MAX_REPORTED_ERRORS) {
                        // +1: record numbers exclude the header row, file line numbers don't
                        errors.add("Line " + (record.getRecordNumber() + 1) + ": " + e.getMessage());
                    }
                }
            }
        }

        log.info("Rate sheet import: {} inserted, {} updated, {} rejected rows",
                inserted, updated, errors.size());
        return new ImportResult(inserted, updated, errors);
    }

    /**
     * Builds the rate block for the drafting prompt: every non-expired rate whose origin AND
     * destination both appear as whole words in the email text, formatted one per line with the
     * sell price (margin already applied). Empty string when the feature is off or nothing matches.
     *
     * @param emailText email body plus parsed attachment text
     * @return prompt-ready rate lines, or {@code ""}
     */
    public String buildRateContext(String emailText) {
        if (!settingsService.isFeatureEnabled(FEATURE_KEY, true)) {
            return "";
        }
        if (emailText == null || emailText.isBlank()) {
            return "";
        }

        List<String> lines = repository.findAllValidOn(LocalDate.now()).stream()
                .filter(r -> containsWord(emailText, r.getOrigin()) && containsWord(emailText, r.getDestination()))
                .limit(MAX_RATES_IN_PROMPT)
                .map(this::formatRateLine)
                .toList();

        if (lines.isEmpty()) {
            return "";
        }
        log.info("Rate lookup matched {} rate(s) for the enquiry", lines.size());
        return String.join("\n", lines);
    }

    /**
     * Upserts one CSV row.
     *
     * @return {@code true} when a new row was inserted, {@code false} when an existing one was updated
     */
    private boolean upsertRow(CSVRecord record) {
        String carrier = requireValue(record, "carrier");
        String origin = requireValue(record, "origin");
        String destination = requireValue(record, "destination");

        BigDecimal rate = new BigDecimal(requireValue(record, "rate"));
        if (rate.signum() < 0) {
            throw new IllegalArgumentException("rate must not be negative");
        }

        String currency = optionalValue(record, "currency", "GBP").toUpperCase();
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be a 3-letter code, got '" + currency + "'");
        }

        BigDecimal margin = new BigDecimal(optionalValue(record, "margin_percent", "0"));

        String validUntilRaw = optionalValue(record, "valid_until", "");
        LocalDate validUntil = validUntilRaw.isEmpty() ? null : LocalDate.parse(validUntilRaw);

        CarrierRate row = repository
                .findByCarrierIgnoreCaseAndOriginIgnoreCaseAndDestinationIgnoreCase(carrier, origin, destination)
                .orElse(null);
        boolean isNew = row == null;
        if (isNew) {
            row = new CarrierRate();
            row.setCarrier(carrier);
            row.setOrigin(origin);
            row.setDestination(destination);
        }
        row.setRate(rate);
        row.setCurrency(currency);
        row.setMarginPercent(margin);
        row.setValidUntil(validUntil);
        row.setUpdatedAt(LocalDateTime.now());
        repository.save(row);
        return isNew;
    }

    private String requireValue(CSVRecord record, String column) {
        if (!record.isMapped(column) || record.get(column) == null || record.get(column).isBlank()) {
            throw new IllegalArgumentException("missing value for '" + column + "'");
        }
        return record.get(column).trim();
    }

    private String optionalValue(CSVRecord record, String column, String defaultValue) {
        if (!record.isMapped(column) || record.get(column) == null || record.get(column).isBlank()) {
            return defaultValue;
        }
        return record.get(column).trim();
    }

    /** Whole-word, case-insensitive match so e.g. "Hull" does not match inside "fully". */
    private boolean containsWord(String text, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(text)
                .find();
    }

    /** One prompt line per rate. Sell price only — the margin itself never reaches the LLM. */
    private String formatRateLine(CarrierRate r) {
        String validity = r.getValidUntil() != null
                ? ", valid until " + r.getValidUntil().format(VALID_UNTIL_DISPLAY)
                : "";
        return String.format("- %s to %s via %s: %s %s%s",
                r.getOrigin(), r.getDestination(), r.getCarrier(),
                r.sellPrice().toPlainString(), r.getCurrency(), validity);
    }
}
