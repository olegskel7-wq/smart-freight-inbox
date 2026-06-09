package com.smartfreight.inbox.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.activation.DataHandler;
import java.io.ByteArrayOutputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AttachmentParserService}.
 *
 * Builds real PDF (PDFBox) and Excel (POI) attachments in memory, wraps them in a MIME message,
 * and verifies the parser pulls their text out — and that malformed/unsupported parts are skipped
 * gracefully rather than throwing.
 */
class AttachmentParserServiceTest {

    private AttachmentParserService parser;
    private Session mailSession;

    @BeforeEach
    void setUp() {
        parser = new AttachmentParserService(new SimpleMeterRegistry());
        mailSession = Session.getInstance(new Properties());
    }

    @Test
    void extractAttachmentText_pdfAttachment_returnsItsText() throws Exception {
        byte[] pdf = buildPdf("Container MSCU1234567 status request");
        MimeMessage msg = messageWithAttachment("Please see attached", pdf, "application/pdf", "request.pdf");

        String text = parser.extractAttachmentText(msg);

        assertThat(text).contains("MSCU1234567");
        assertThat(text).contains("request.pdf");
    }

    @Test
    void extractAttachmentText_excelAttachment_returnsCellValues() throws Exception {
        byte[] xlsx = buildXlsx("TCKU9876543", "Felixstowe");
        MimeMessage msg = messageWithAttachment("Rates attached", xlsx,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "rates.xlsx");

        String text = parser.extractAttachmentText(msg);

        assertThat(text).contains("TCKU9876543");
        assertThat(text).contains("Felixstowe");
    }

    @Test
    void extractAttachmentText_noAttachments_returnsEmpty() throws Exception {
        MimeMessage msg = new MimeMessage(mailSession);
        msg.setText("Just a plain body, no attachments");

        assertThat(parser.extractAttachmentText(msg)).isEmpty();
    }

    @Test
    void extractAttachmentText_corruptPdf_isSkippedNotThrown() throws Exception {
        byte[] garbage = "%PDF-1.4 not really a pdf".getBytes();
        MimeMessage msg = messageWithAttachment("Body", garbage, "application/pdf", "broken.pdf");

        // Must degrade gracefully: no exception, no extracted text from the broken part.
        String text = parser.extractAttachmentText(msg);
        assertThat(text).doesNotContain("broken");
    }

    @Test
    void extractAttachmentText_unsupportedType_isSkipped() throws Exception {
        byte[] zip = {0x50, 0x4b, 0x03, 0x04};
        MimeMessage msg = messageWithAttachment("Body", zip, "application/zip", "archive.zip");

        assertThat(parser.extractAttachmentText(msg)).isEmpty();
    }

    // --- helpers ---------------------------------------------------------

    private MimeMessage messageWithAttachment(String body, byte[] attachment,
                                              String contentType, String filename) throws Exception {
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(body);

        MimeBodyPart attachPart = new MimeBodyPart();
        attachPart.setDataHandler(new DataHandler(new ByteArrayDataSource(attachment, contentType)));
        attachPart.setFileName(filename);
        attachPart.setDisposition(Part.ATTACHMENT);

        MimeMultipart multipart = new MimeMultipart();
        multipart.addBodyPart(textPart);
        multipart.addBodyPart(attachPart);

        MimeMessage msg = new MimeMessage(mailSession);
        msg.setContent(multipart);
        return msg;
    }

    private byte[] buildPdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private byte[] buildXlsx(String... values) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sheet1");
            Row row = sheet.createRow(0);
            for (int i = 0; i < values.length; i++) {
                row.createCell(i).setCellValue(values[i]);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);
            return baos.toByteArray();
        }
    }
}
