package ir.ipaam.fileservice.domain.model.valueobject;

public class Span {
    public final String text;
    public final Style style;

    public Span(String text, Style style) {
        this.text = text;
        this.style = style;
    }
}
