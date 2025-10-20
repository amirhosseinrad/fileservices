package ir.ipaam.fileservice.application.service;

import ir.ipaam.fileservice.application.service.htmltopdf.ResourceResolver;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HtmlToPdfValidationTest {

    private final HtmlToPdfService service = new HtmlToPdfService();
    private final ResourceResolver resolver = src -> InputStream.nullInputStream();

    @Test
    void malformedHtmlReportsLineNumber() {
        String html = """
                <?xml version=\"1.0\" encoding=\"UTF-8\"?>
                <html xmlns=\"http://www.w3.org/1999/xhtml\">
                  <body>
                    <div>
                      <p>Test</div>
                  </body>
                </html>
                """;
        String css = "body { color: #000; }";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.convertXhtmlToPdf(
                        new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)),
                        new ByteArrayInputStream(css.getBytes(StandardCharsets.UTF_8)),
                        Collections.emptyMap(),
                        resolver));

        assertThat(ex.getMessage()).contains("line 5");
    }

    @Test
    void malformedCssReportsLineNumber() {
        String html = """
                <?xml version=\"1.0\" encoding=\"UTF-8\"?>
                <html xmlns=\"http://www.w3.org/1999/xhtml\">
                  <body><p>Ok</p></body>
                </html>
                """;
        String css = """
                p {
                  color red
                }
                """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.convertXhtmlToPdf(
                        new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)),
                        new ByteArrayInputStream(css.getBytes(StandardCharsets.UTF_8)),
                        Collections.emptyMap(),
                        resolver));

        assertThat(ex.getMessage()).contains("line 2");
    }
}
