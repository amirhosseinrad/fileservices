package ir.ipaam.fileservice.application.service;

import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.ArabicShapingException;
import com.ibm.icu.text.Bidi;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;
import org.jsoup.select.NodeVisitor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@Service
public class HtmlCssPdfGenerator {

    private static final String DEFAULT_PERSIAN_FONT_CSS = """
            @font-face { font-family: 'IranSans'; src: local('IranSans'); }
            @font-face { font-family: 'Vazir';    src: local('Vazir'); }
            body { font-family: 'Vazir','IranSans',sans-serif; }
            /* Let the browser infer paragraph direction from the text itself */
            :lang(fa), :lang(ar) { unicode-bidi: plaintext; }
            """;

    private static final String IRANSANS_FONT_RESOURCE = "/fonts/IranSans.ttf";
    private static final String VAZIR_FONT_RESOURCE    = "/fonts/Vazir.ttf";

    // Arabic & Persian letters (base blocks), not presentation forms:
    private static final Pattern ARABIC_BASE = Pattern.compile("[\\u0600-\\u06FF\\u0750-\\u077F]");
    // Presentation forms ranges (skip shaping if we already see these):
    private static final Pattern ARABIC_PRESENTATION =
            Pattern.compile("[\\uFB50-\\uFDFF\\uFE70-\\uFEFF]");

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
        builder.useFont(() -> getFontStream(VAZIR_FONT_RESOURCE), "Vazir");
        builder.useFont(() -> getFontStream(IRANSANS_FONT_RESOURCE), "IranSans");
    }

    private InputStream getFontStream(String path) {
        InputStream stream = getClass().getResourceAsStream(path);
        if (stream == null) throw new IllegalStateException("Font not found: " + path);
        return stream;
    }

    private String buildDocument(String htmlContent, String cssContent) {
        String safeHtml = htmlContent == null ? "" : normalizeHtmlMarkup(htmlContent);
        String cssWithFont = ensureDefaultFontCss(cssContent);

        if (containsHtmlTag(safeHtml)) {
            return injectCssIntoExistingDocument(safeHtml, cssWithFont);
        }
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">" +
                "<style>" + cssWithFont + "</style></head><body>" +
                safeHtml + "</body></html>";
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
            return htmlContent.substring(0, headCloseIndex) + styleTag + htmlContent.substring(headCloseIndex);
        }
        int bodyIndex = lower.indexOf("<body");
        if (bodyIndex != -1) {
            return "<html><head>" + styleTag + "</head>" + htmlContent.substring(bodyIndex);
        }
        return "<html><head>" + styleTag + "</head><body>" + htmlContent + "</body></html>";
    }

    private String ensureDefaultFontCss(String cssContent) {
        String existing = cssContent == null ? "" : cssContent.trim();
        return existing.isEmpty() ? DEFAULT_PERSIAN_FONT_CSS : DEFAULT_PERSIAN_FONT_CSS + "\n" + existing;
    }

    // ---------- The important part: robust RTL shaping ----------
    private String normalizeHtmlMarkup(String htmlContent) {
        if (htmlContent.isEmpty()) return htmlContent;

        Document doc = Jsoup.parse(htmlContent);
        doc.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(Entities.EscapeMode.xhtml)
                .charset(StandardCharsets.UTF_8)
                .prettyPrint(false);

        // Walk only TEXT nodes
        doc.body().traverse((NodeVisitor) (node, depth) -> {
            if (node instanceof org.jsoup.nodes.TextNode tn) {
                String parent = node.parent() != null ? node.parent().nodeName() : "";
                if ("script".equalsIgnoreCase(parent) || "style".equalsIgnoreCase(parent)) return;

                String text = tn.getWholeText();
                if (!containsArabicBase(text)) return;

                // 1) Reorder to visual with ICU Bidi (handles mixed LTR/RTL)
                //    Use default paragraph direction so mixed contexts are handled naturally.
                String visual = new Bidi(text, Bidi.LEVEL_DEFAULT_LTR)
                        .writeReordered(Bidi.DO_MIRRORING);


                // 2) Shape only Arabic runs in the VISUAL string
                String shaped = shapeArabicRunsInVisual(visual);

                tn.text(shaped);
            }
        });

        String normalized = doc.outerHtml();

        // Escape stray ampersands (prevents SAX “entity name must follow &”)
        normalized = normalized.replaceAll("&(?!#?\\w+;)", "&amp;");

        // Replace named spaces with numeric (XHTML-safe)
        normalized = normalized
                .replace("&nbsp;", "&#160;")
                .replace("&ensp;", "&#8194;")
                .replace("&emsp;", "&#8195;")
                .replace("&thinsp;", "&#8201;");

        return normalized;
    }

    private static boolean containsArabicBase(String s) {
        return s != null && ARABIC_BASE.matcher(s).find();
    }

    // Shape Arabic-only runs; skip if already in presentation forms (avoids double-shaping).
    private static String shapeArabicRunsInVisual(String visual) {
        if (visual == null || visual.isEmpty()) return visual;

        StringBuilder out = new StringBuilder(visual.length());
        int i = 0, n = visual.length();

        while (i < n) {
            // Non-Arabic chunk
            int start = i;
            while (i < n && !isArabicBaseChar(visual.charAt(i))) i++;
            if (i > start) out.append(visual, start, i);

            // Arabic chunk
            start = i;
            while (i < n && isArabicBaseChar(visual.charAt(i))) i++;
            if (i > start) {
                String chunk = visual.substring(start, i);
                // If already has presentation forms, don't reshape.
                if (ARABIC_PRESENTATION.matcher(chunk).find()) {
                    out.append(chunk);
                } else {
                    try {
                        ArabicShaping shaper = new ArabicShaping(
                                ArabicShaping.LETTERS_SHAPE | ArabicShaping.TEXT_DIRECTION_LOGICAL
                        );
                        out.append(shaper.shape(chunk));
                    } catch (ArabicShapingException e) {
                        // Fallback to unshaped chunk
                        out.append(chunk);
                    }
                }
            }
        }
        return out.toString();
    }

    private static boolean isArabicBaseChar(char c) {
        return (c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F);
    }
}
