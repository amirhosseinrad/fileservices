package ir.ipaam.fileservice.application.service;

import ir.ipaam.fileservice.domain.event.PdfCreatedEvent;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

@Service
public class PdfGenerator {

    public byte[] generate(PdfCreatedEvent event) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDDocument doc = new PDDocument();
            PDPage page = new PDPage();
            doc.addPage(page);

            PDPageContentStream content = new PDPageContentStream(doc, page);

            try (InputStream fontStream = getClass().getResourceAsStream("/fonts/IranSans.ttf")) {
                if (fontStream == null) {
                    throw new IllegalStateException("Font not found in resources: /fonts/IranSans.ttf");
                }
                PDType0Font font = PDType0Font.load(doc, fontStream, true);
                content.setFont(font, 14);
            }

            content.beginText();
            content.newLineAtOffset(100, 700);

            float leading = 16f;
            String[] lines = (event.getText() == null ? "" : event.getText()).split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    content.newLineAtOffset(0, -leading);
                }
                content.showText(lines[i]);
            }
            content.endText();
            content.close();

            doc.save(out);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("PDF generation failed", e);
        }
    }
}
