package ir.ipaam.fileservice.application.service.htmltopdf.model;

import java.awt.Color;

public class Style {
    public boolean bold = false;
    public boolean italic = false;
    public float fontSize = 16f;
    public Color color = Color.BLACK;
    public boolean underline = false;
    public String textAlign = "right";
    public Float lineHeightPx = null;
    public Float lineHeightMult = null;
    public float marginTopPx = 0f;
    public float marginBottomPx = 0f;

    public Style copy() {
        Style s = new Style();
        s.bold = this.bold;
        s.italic = this.italic;
        s.fontSize = this.fontSize;
        s.color = this.color;
        s.textAlign = this.textAlign;
        s.lineHeightPx = this.lineHeightPx;
        s.lineHeightMult = this.lineHeightMult;
        s.marginTopPx = this.marginTopPx;
        s.marginBottomPx = this.marginBottomPx;
        s.underline = this.underline;
        return s;
    }
}
