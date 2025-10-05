package ir.ipaam.fileservice.application.service;

import ir.ipaam.fileservice.domain.event.PdfCreatedEvent;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.regex.Pattern;

@Service
public class PdfGenerator {

    private static final Pattern RTL_PATTERN = Pattern.compile("[\\p{InARABIC}]");

    private static final String TEXT_LAYOUT_CSS = """
            body {
                margin: 48px 64px;
                font-size: 14px;
                line-height: 1.6;
                background-color: #ffffff;
            }
            .text-wrapper {
                display: flex;
                flex-direction: column;
                gap: 8px;
                width: 100%;
            }
            .text-line {
                font-family: 'Vazir', 'IranSans', sans-serif;
                word-break: break-word;
                white-space: pre-wrap;
            }
            .text-line.rtl {
                direction: rtl;
                unicode-bidi: isolate;
                text-align: right;
            }
            .text-line.ltr {
                direction: ltr;
                unicode-bidi: isolate;
                text-align: left;
            }
            .text-line.empty {
                min-height: 1.2em;
            }
            """;

    private final HtmlCssPdfGenerator htmlCssPdfGenerator;

    public PdfGenerator(HtmlCssPdfGenerator htmlCssPdfGenerator) {
        this.htmlCssPdfGenerator = htmlCssPdfGenerator;
    }

    public byte[] generate(String text) {
        return generate(new PdfCreatedEvent(null, text, null));
    }

    public byte[] generate(PdfCreatedEvent event) {
        String text = event.getText();
        String htmlDocument = wrapTextAsHtml(text == null ? "" : text);
        return htmlCssPdfGenerator.generate(htmlDocument, TEXT_LAYOUT_CSS);
    }

    private String wrapTextAsHtml(String text) {
        StringBuilder builder = new StringBuilder();
        builder.append("<div class=\"text-wrapper\">");

        Arrays.stream(text.split("\\R", -1))
                .map(this::sanitizeLine)
                .forEach(line -> appendLine(builder, line, escapeHtml(line)));

        builder.append("</div>");
        return builder.toString();
    }

    private String sanitizeLine(String line) {
        return line == null ? "" : line;
    }

    private void appendLine(StringBuilder builder, String original, String escaped) {
        boolean isEmpty = escaped.isEmpty();
        String cssClass = containsRtlCharacters(original) ? "rtl" : "ltr";
        String classes = "text-line " + cssClass + (isEmpty ? " empty" : "");

        builder.append("<div class=\"")
                .append(classes)
                .append("\" dir=\"")
                .append(cssClass)
                .append("\"");

        if ("rtl".equals(cssClass)) {
            builder.append(" lang=\"fa\"");
        }

        builder.append(isEmpty ? ">&#8203;" : ">")
                .append(isEmpty ? "" : escaped)
                .append("</div>");
    }

    private boolean containsRtlCharacters(String value) {
        return value != null && RTL_PATTERN.matcher(value).find();
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }

        String escaped = value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
                .replace("\u200c", "&#8204;");

        return escaped;
    }
}
