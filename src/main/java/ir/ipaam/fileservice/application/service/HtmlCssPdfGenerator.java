package ir.ipaam.fileservice.application.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

@Service
public class HtmlCssPdfGenerator {

    public byte[] generate(String htmlContent, String cssContent) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            String document = buildDocument(htmlContent, cssContent);

            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(document, null);
            builder.useFont(this::loadIranSansFont, "IranSans");
            builder.toStream(outputStream);
            builder.run();

            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate PDF from HTML content", e);
        }
    }

    private InputStream loadIranSansFont() {
        InputStream stream = getClass().getResourceAsStream("/fonts/IranSans.ttf");
        if (stream == null) {
            throw new IllegalStateException("Font not found in resources: /fonts/IranSans.ttf");
        }
        return stream;
    }

    private String buildDocument(String htmlContent, String cssContent) {
        String safeHtml = htmlContent == null ? "" : normalizeHtmlEntities(htmlContent);
        String safeCss = cssContent == null ? "" : cssContent;

        if (containsHtmlTag(safeHtml)) {
            return injectCssIntoExistingDocument(safeHtml, safeCss);
        }

        StringBuilder builder = new StringBuilder();
        builder.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">");
        if (!safeCss.isBlank()) {
            builder.append("<style>").append(safeCss).append("</style>");
        }
        builder.append("</head><body>").append(safeHtml).append("</body></html>");
        return builder.toString();
    }

    private boolean containsHtmlTag(String htmlContent) {
        String lower = htmlContent.toLowerCase();
        return lower.contains("<html") && lower.contains("</html>");
    }

    private String injectCssIntoExistingDocument(String htmlContent, String cssContent) {
        if (cssContent.isBlank()) {
            return htmlContent;
        }

        String lower = htmlContent.toLowerCase();
        int headCloseIndex = lower.indexOf("</head>");
        if (headCloseIndex != -1) {
            return htmlContent.substring(0, headCloseIndex) +
                    "<style>" + cssContent + "</style>" +
                    htmlContent.substring(headCloseIndex);
        }

        int headOpenIndex = lower.indexOf("<head");
        if (headOpenIndex != -1) {
            int headEnd = lower.indexOf('>', headOpenIndex);
            if (headEnd != -1) {
                return htmlContent.substring(0, headEnd + 1) +
                        "<style>" + cssContent + "</style>" +
                        htmlContent.substring(headEnd + 1);
            }
        }

        int bodyIndex = lower.indexOf("<body");
        if (bodyIndex != -1) {
            return "<html><head><style>" + cssContent + "</style></head>" + htmlContent.substring(bodyIndex);
        }

        return "<html><head><style>" + cssContent + "</style></head><body>" + htmlContent + "</body></html>";
    }

    private String normalizeHtmlEntities(String htmlContent) {
        if (htmlContent.isEmpty()) {
            return htmlContent;
        }

        return htmlContent.replace("&nbsp;", "&#160;");
    }
}
