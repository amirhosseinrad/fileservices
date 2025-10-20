package ir.ipaam.fileservice.domain.model.valueobject;

public class SpanRun {
    public final int start;
    public final int end;
    public final Style style;

    public SpanRun(int start, int end, Style style) {
        this.start = start;
        this.end = end;
        this.style = style;
    }
}
