package ir.ipaam.fileservice.application.service;

import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.ArabicShapingException;
import com.ibm.icu.text.Bidi;
import ir.ipaam.fileservice.domain.event.PdfCreatedEvent;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.regex.Pattern;

@Service
public class PdfGenerator {

    public byte[] generate(String text) {
        return generate(new PdfCreatedEvent(null, text, null));
    }

    private static final Pattern RTL_PATTERN = Pattern.compile("[\\p{InArabic}]");

    private static final ArabicShaping ARABIC_SHAPING = new ArabicShaping(
            ArabicShaping.LETTERS_SHAPE | ArabicShaping.TEXT_DIRECTION_LOGICAL
    );

    public byte[] generate(PdfCreatedEvent event) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDDocument doc = new PDDocument();
            PDPage page = new PDPage();
            doc.addPage(page);

            PDPageContentStream content = new PDPageContentStream(doc, page);

            PDFont font;
            try (InputStream fontStream = getClass().getResourceAsStream("/fonts/IranSans.ttf")) {
                if (fontStream == null) {
                    throw new IllegalStateException("Font not found in resources: /fonts/IranSans.ttf");
                }
                font = PDType0Font.load(doc, fontStream, false);
            }

            float fontSize = 14f;
            float leading = 16f;
            float margin = 100f;
            float startY = page.getMediaBox().getHeight() - margin;
            float rightLimit = page.getMediaBox().getWidth() - margin;

            String[] lines = (event.getText() == null ? "" : event.getText()).split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                PreparedLine preparedLine = prepareLine(lines[i]);
                float yPosition = startY - (i * leading);
                float xPosition;

                if (preparedLine.rtl()) {
                    float textWidth = font.getStringWidth(preparedLine.text()) / 1000 * fontSize;
                    xPosition = rightLimit - textWidth;
                } else {
                    xPosition = margin;
                }

                content.beginText();
                content.setFont(font, fontSize);
                content.newLineAtOffset(xPosition, yPosition);
                content.showText(preparedLine.text());
                content.endText();
            }

            content.close();

            doc.save(out);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("PDF generation failed", e);
        }
    }

    private PreparedLine prepareLine(String line) {
        if (line == null || line.isEmpty()) {
            return new PreparedLine("", false);
        }

        if (!containsRtlCharacters(line)) {
            return new PreparedLine(line, false);
        }

        try {
            String shaped = ARABIC_SHAPING.shape(line);
            Bidi bidi = new Bidi(shaped, Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);
            bidi.setReorderingMode(Bidi.REORDER_DEFAULT);
            String visual = bidi.writeReordered(
                    Bidi.DO_MIRRORING |
                            Bidi.OUTPUT_REVERSE |
                            Bidi.INSERT_LRM_FOR_NUMBERS
            );
            String normalized = Normalizer.normalize(visual, Normalizer.Form.NFKC);
            return new PreparedLine(normalized, true);
        } catch (ArabicShapingException e) {
            throw new RuntimeException("Unable to shape RTL text", e);
        }
    }

    private boolean containsRtlCharacters(String line) {
        return RTL_PATTERN.matcher(line).find();
    }

    private record PreparedLine(String text, boolean rtl) {
    }
}
