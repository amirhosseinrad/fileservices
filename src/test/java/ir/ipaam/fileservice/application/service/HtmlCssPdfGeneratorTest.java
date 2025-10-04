package ir.ipaam.fileservice.application.service;

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
    void shouldInjectIranSansFontCssIntoGeneratedDocument() throws Exception {
        Method buildDocument = HtmlCssPdfGenerator.class.getDeclaredMethod("buildDocument", String.class, String.class);
        buildDocument.setAccessible(true);

        String document = (String) buildDocument.invoke(generator, "<p>سلام دنیا</p>", "");

        assertTrue(document.contains("font-family: 'IranSans'"), "Generated document should reference IranSans font");
    }
}
