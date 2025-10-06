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
import java.util.Arrays;
import java.util.regex.Pattern;

@Service
public class PdfGenerator {

    private static final ArabicShaping ARABIC_SHAPING =
            new ArabicShaping(ArabicShaping.LETTERS_SHAPE | ArabicShaping.TEXT_DIRECTION_VISUAL_LTR);

    private static final Pattern RTL_PATTERN = Pattern.compile("[\\u0600-\\u06FF]");

    // Minimal, bidi-aware CSS. Fonts are registered inside HtmlCssPdfGenerator.
    private static final String TEXT_LAYOUT_CSS = """
        body {
          margin: 48px 64px;
          font-size: 14px;
          line-height: 1.8;
          background: #fff;
          direction: rtl;
          unicode-bidi: isolate-override;
          text-align: right;
          font-family: 'Vazir','IranSans',sans-serif;
        }
        .text-wrapper { display: flex; flex-direction: column; gap: 8px; }
        .text-line { white-space: pre-wrap; word-break: break-word; }
        .ltr { direction: ltr; unicode-bidi: isolate; text-align: left; }
        """;

    private final HtmlCssPdfGenerator htmlCssPdfGenerator;

    public PdfGenerator(HtmlCssPdfGenerator htmlCssPdfGenerator) {
        this.htmlCssPdfGenerator = htmlCssPdfGenerator;
    }

    public byte[] generate(String text) {
        return generate(new PdfCreatedEvent(null, text, null));
    }

    public byte[] generate(PdfCreatedEvent event) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             PDDocument doc = new PDDocument()) {

            PDPage page = new PDPage();
            doc.addPage(page);

            PDFont font;
            try (InputStream fontStream = getClass().getResourceAsStream("/fonts/IranSans.ttf")) {
                if (fontStream == null)
                    throw new IllegalStateException("Font not found: /fonts/IranSans.ttf");
                font = PDType0Font.load(doc, fontStream, true);
            }

            float fontSize = 14f;
            float leading = 20f;
            float margin = 72f;
            float yStart = page.getMediaBox().getHeight() - margin;
            float rightMargin = page.getMediaBox().getWidth() - margin;

            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                String[] lines = normalizePersian(event.getText()).split("\\R", -1);

                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (line.isEmpty()) continue;

                    boolean rtl = RTL_PATTERN.matcher(line).find();
                    if (rtl) {
                        // Shape and reorder the text, then REVERSE it for PDFBox drawing
                        line = shapeAndReorder(line);
                    }

                    float textWidth = font.getStringWidth(line) / 1000 * fontSize;
                    float xPos = rtl ? rightMargin - textWidth : margin;
                    float yPos = yStart - (i * leading);

                    content.beginText();
                    content.setFont(font, fontSize);
                    // Draw RTL lines by moving from right to left
                    content.newLineAtOffset(xPos, yPos);
                    content.showText(line);
                    content.endText();
                }
            }

            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("PDF generation failed", e);
        }
    }


    /** Normalize Persian text: fix Arabic Kaf/Ya variants, NFC. */
    private String normalizePersian(String text) {
        String n = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            switch (ch) {
                case 'ك' -> sb.append('ک');               // Arabic Kaf -> Persian Ke
                case 'ي', 'ى', 'ے' -> sb.append('ی');     // Arabic Ya forms -> Persian Yeh
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** Build very simple HTML where each input line becomes a div. */
    private String wrapAsRtlHtml(String text) {
        StringBuilder b = new StringBuilder();
        b.append("<div class=\"text-wrapper\">");
        Arrays.stream(text.split("\\R", -1)).forEach(line -> {
            boolean isRtl = RTL_PATTERN.matcher(line).find();
            String cls = isRtl ? "text-line" : "text-line ltr";
            b.append("<div class=\"").append(cls).append("\">")
                    .append(escapeHtml(line.isEmpty() ? "\u200B" : line))
                    .append("</div>");
        });
        b.append("</div>");
        return b.toString();
    }

    private String escapeHtml(String s) {
        return s.replace("&","&amp;")
                .replace("<","&lt;")
                .replace(">","&gt;")
                .replace("\"","&quot;")
                .replace("'","&#39;");
    }

    /** Shape & reorder Arabic text correctly for PDFBox */
    private String shapeAndReorder(String line) {
        try {
            // Shape without visual reordering
            String shaped = new ArabicShaping(
                    ArabicShaping.LETTERS_SHAPE | ArabicShaping.LENGTH_GROW_SHRINK
            ).shape(line);

            // Manually reverse shaped text for correct RTL display
            return new StringBuilder(shaped).reverse().toString();
        } catch (ArabicShapingException e) {
            return line;
        }
    }

}
