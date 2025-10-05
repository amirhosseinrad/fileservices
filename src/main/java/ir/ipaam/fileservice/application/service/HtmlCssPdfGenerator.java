package ir.ipaam.fileservice.application.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Service
public class HtmlCssPdfGenerator {

    private static final String DEFAULT_PERSIAN_FONT_CSS = """
            @font-face {
                font-family: 'IranSans';
                src: local('IranSans');
            }
            @font-face {
                font-family: 'Vazir';
                src: local('Vazir');
            }
            body {
                font-family: 'Vazir', 'IranSans', sans-serif;
            }
            """;

    private static final String IRANSANS_FONT_RESOURCE = "/fonts/IranSans.ttf";
    private static final String VAZIR_FONT_RESOURCE = "/fonts/Vazir.ttf";

    public byte[] generate(String htmlContent, String cssContent) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            String document = buildDocument(htmlContent, cssContent);

            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useHarfBuzzShaping(true);
            builder.useUnicodeBidiReordering(true);
            builder.withHtmlContent(document, null);
            registerFonts(builder);
            builder.toStream(outputStream);
            builder.run();

            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate PDF from HTML content", e);
        }
    }

    private void registerFonts(PdfRendererBuilder builder) {
        builder.useFont(this::loadIranSansFont, "IranSans");
        builder.useFont(this::loadVazirFontOrFallback, "Vazir");
    }

    private InputStream loadIranSansFont() {
        return loadFont(IRANSANS_FONT_RESOURCE);
    }

    private InputStream loadVazirFontOrFallback() {
        InputStream stream = getClass().getResourceAsStream(VAZIR_FONT_RESOURCE);
        if (stream != null) {
            return stream;
        }

        return loadIranSansFont();
    }

    private InputStream loadFont(String resourcePath) {
        InputStream stream = getClass().getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IllegalStateException("Font not found in resources: " + resourcePath);
        }
        return stream;
    }

    private String buildDocument(String htmlContent, String cssContent) {
        String safeHtml = htmlContent == null ? "" : normalizeHtmlMarkup(htmlContent);
        String cssWithFont = ensureDefaultFontCss(cssContent);

        if (containsHtmlTag(safeHtml)) {
            return injectCssIntoExistingDocument(safeHtml, cssWithFont);
        }

        StringBuilder builder = new StringBuilder();
        builder.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">");
        builder.append("<style>").append(cssWithFont).append("</style>");
        builder.append("</head><body>").append(safeHtml).append("</body></html>");
        return builder.toString();
    }

    private boolean containsHtmlTag(String htmlContent) {
        String lower = htmlContent.toLowerCase();
        return lower.contains("<html") && lower.contains("</html>");
    }

    private String injectCssIntoExistingDocument(String htmlContent, String cssContent) {
        String lower = htmlContent.toLowerCase();
        String styleTag = "<style>" + cssContent + "</style>";
        int headCloseIndex = lower.indexOf("</head>");
        if (headCloseIndex != -1) {
            return htmlContent.substring(0, headCloseIndex) +
                    styleTag +
                    htmlContent.substring(headCloseIndex);
        }

        int headOpenIndex = lower.indexOf("<head");
        if (headOpenIndex != -1) {
            int headEnd = lower.indexOf('>', headOpenIndex);
            if (headEnd != -1) {
                return htmlContent.substring(0, headEnd + 1) +
                        styleTag +
                        htmlContent.substring(headEnd + 1);
            }
        }

        int bodyIndex = lower.indexOf("<body");
        if (bodyIndex != -1) {
            return "<html><head>" + styleTag + "</head>" + htmlContent.substring(bodyIndex);
        }

        return "<html><head>" + styleTag + "</head><body>" + htmlContent + "</body></html>";
    }

    private String ensureDefaultFontCss(String cssContent) {
        String existingCss = cssContent == null ? "" : cssContent.trim();
        if (existingCss.isEmpty()) {
            return DEFAULT_PERSIAN_FONT_CSS;
        }

        return DEFAULT_PERSIAN_FONT_CSS + "\n" + existingCss;
    }

    private String normalizeHtmlMarkup(String htmlContent) {
        if (htmlContent.isEmpty()) {
            return htmlContent;
        }

        Document document = Jsoup.parse(htmlContent);
        document.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(Entities.EscapeMode.xhtml)
                .charset(StandardCharsets.UTF_8)
                .prettyPrint(false);

        String normalized = document.outerHtml();
        return normalized
                .replace("&nbsp;", "&#160;")
                .replace(" ", "&#160;");
    }
}
