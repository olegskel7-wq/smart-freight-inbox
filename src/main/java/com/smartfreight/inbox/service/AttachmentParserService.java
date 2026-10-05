package com.smartfreight.inbox.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Extracts plain text from email attachments (Phase 1.1).
 *
 * <p>Most freight RFQs arrive as a short email body plus a PDF or Excel attachment carrying the
 * actual request (container numbers, addresses, rates). Without parsing them the AI pipeline is
 * blind to half the input. This service walks the MIME tree, pulls text out of PDF (Apache PDFBox)
 * and Excel (Apache POI) attachments, and returns a single labelled block ready to feed into the
 * extraction/drafting prompts.
 *
 * <p>Resilience: parsing is best-effort. A corrupt or unsupported attachment is logged and skipped,
 * never propagated — a bad attachment must not stop a ticket from being created. Output is capped
 * to {@link #MAX_TOTAL_CHARS} to keep prompts bounded and protect the heap.
 */
@Service
public class AttachmentParserService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentParserService.class);

    /** Hard cap on the combined extracted attachment text — keeps prompts bounded. */
    private static final int MAX_TOTAL_CHARS = 8000;

    /** Skip individual attachments larger than this (bytes) — a huge scan/PDF is rarely worth parsing. */
    private static final long MAX_ATTACHMENT_BYTES = 5L * 1024 * 1024;

    private final Counter parsedCounter;
    private final Counter skippedCounter;

    public AttachmentParserService(MeterRegistry meterRegistry) {
        this.parsedCounter = Counter.builder("smartfreight_attachments_total")
                .description("Email attachments processed by the parser")
                .tag("result", "parsed")
                .register(meterRegistry);
        this.skippedCounter = Counter.builder("smartfreight_attachments_total")
                .description("Email attachments processed by the parser")
                .tag("result", "skipped")
                .register(meterRegistry);
    }

    /**
     * Extracts and concatenates text from all supported attachments in the message.
     *
     * @param message the in-memory email message
     * @return labelled attachment text, or an empty string if there are no parseable attachments
     */
    public String extractAttachmentText(MimeMessage message) {
        StringBuilder out = new StringBuilder();
        try {
            Object content = message.getContent();
            if (content instanceof Multipart multipart) {
                collect(multipart, out);
            }
        } catch (Exception e) {
            // getContent() itself can fail on malformed MIME — degrade gracefully.
            log.warn("Could not read message content for attachment parsing: {}", e.getMessage());
        }
        return out.toString().trim();
    }

    /** Recursively walks a multipart tree, appending extracted text from each attachment part. */
    private void collect(Multipart multipart, StringBuilder out) throws MessagingException, IOException {
        for (int i = 0; i < multipart.getCount() && out.length() < MAX_TOTAL_CHARS; i++) {
            BodyPart part = multipart.getBodyPart(i);

            // Nested multipart (e.g. multipart/mixed → multipart/alternative) — recurse.
            if (part.getContent() instanceof Multipart nested) {
                collect(nested, out);
                continue;
            }

            String filename = part.getFileName();
            boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())
                    || (filename != null && !filename.isBlank());
            if (!isAttachment || filename == null) {
                continue;
            }

            String text = parsePart(part, filename);
            if (text != null && !text.isBlank()) {
                out.append("\n--- Attachment: ").append(filename).append(" ---\n")
                        .append(text.strip()).append("\n");
                parsedCounter.increment();
            }
        }
    }

    /** Dispatches a single attachment to the right parser by file extension. Never throws. */
    private String parsePart(BodyPart part, String filename) {
        String lower = filename.toLowerCase();
        try {
            if (part.getSize() > MAX_ATTACHMENT_BYTES) {
                log.info("Attachment too large ({} bytes), skipping", part.getSize());
                skippedCounter.increment();
                return null;
            }

            byte[] bytes;
            try (InputStream is = part.getInputStream()) {
                bytes = is.readAllBytes();
            }

            if (lower.endsWith(".pdf")) {
                return parsePdf(bytes);
            }
            if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
                return parseExcel(bytes);
            }

            log.debug("Unsupported attachment type '{}', skipping", filename);
            skippedCounter.increment();
            return null;

        } catch (Exception e) {
            log.warn("Failed to parse attachment: {}", e.getMessage());
            skippedCounter.increment();
            return null;
        }
    }

    private String parsePdf(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    /** Reads every cell of every sheet as text. {@link WorkbookFactory} auto-detects .xls vs .xlsx. */
    private String parseExcel(byte[] bytes) throws IOException {
        DataFormatter formatter = new DataFormatter();
        StringBuilder sb = new StringBuilder();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        String value = formatter.formatCellValue(cell);
                        if (!value.isBlank()) {
                            sb.append(value).append('\t');
                        }
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }
}
