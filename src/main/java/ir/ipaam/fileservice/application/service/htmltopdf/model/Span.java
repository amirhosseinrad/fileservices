package ir.ipaam.fileservice.application.service.htmltopdf.model;

public class Span {
    public final String text;
    public final Style style;

    public Span(String text, Style style) {
        this.text = text;
        this.style = style;
    }
}
