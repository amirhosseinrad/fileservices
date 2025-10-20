package ir.ipaam.fileservice.domain.model.valueobject;

import java.awt.font.TextLayout;

public class Line {
    public final String text;
    public final int width;
    public final int height;
    public final int ascent;
    public final TextLayout layout;
    public final String align;

    public Line(String text, int width, int height, int ascent) {
        this.text = text;
        this.width = width;
        this.height = height;
        this.ascent = ascent;
        this.layout = null;
        this.align = "right";
    }

    public Line(TextLayout layout, int width, int height, int ascent, String align) {
        this.text = null;
        this.width = width;
        this.height = height;
        this.ascent = ascent;
        this.layout = layout;
        this.align = align == null ? "right" : align;
    }
}
