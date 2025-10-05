package ir.ipaam.fileservice.application.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlCssPdfGeneratorTest {

    private final HtmlCssPdfGenerator generator = new HtmlCssPdfGenerator();

    @Test
    void shouldGeneratePdfFromHtmlAndCss() {
        String html = "<div class='title'>Hello World</div>";
        String css = ".title { font-size: 24px; color: #333333; text-align: center; }";

        byte[] pdfBytes = generator.generate(html, css);

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 0, "Generated PDF should not be empty");
    }

    @Test
    void shouldGeneratePdfWhenHtmlDocumentProvided() {
        String html = "<!DOCTYPE html><html><head><title>Sample</title></head><body><p>سلام دنیا</p></body></html>";

        byte[] pdfBytes = generator.generate(html, "");

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 0, "Generated PDF should not be empty");
    }

    @Test
    void shouldInjectPersianFontCssIntoGeneratedDocument() throws Exception {
        Method buildDocument = HtmlCssPdfGenerator.class.getDeclaredMethod("buildDocument", String.class, String.class);
        buildDocument.setAccessible(true);

        String document = (String) buildDocument.invoke(generator, "<p>سلام دنیا</p>", "");

        assertTrue(document.contains("font-family: 'Vazir'"), "Generated document should reference Vazir font");
        assertTrue(document.contains("font-family: 'IranSans'"), "Generated document should include IranSans fallback");
    }

    @Test
    void generatesPdfWithCorrectPersianGlyphOrder() throws Exception {
        String persianParagraph = "این یک پاراگراف نمونه فارسی برای آزمایش ترتیب صحیح حروف است.";
        String html = "<html><body><p dir=\"rtl\">" + persianParagraph + "</p></body></html>";

        byte[] pdfBytes = generator.generate(html, null);

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper textStripper = new PDFTextStripper();
            String extracted = textStripper.getText(document)
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .replaceAll("\\s+", " ")
                    .trim();

            assertTrue(extracted.contains(persianParagraph),
                    () -> "Expected extracted text to contain the Persian paragraph, but was: " + extracted);
        }
    }
}
