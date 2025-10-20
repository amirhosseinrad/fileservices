package ir.ipaam.fileservice.application.service.htmltopdf.model;

import java.awt.image.BufferedImage;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

public class Block {
    public String align = "right";
    public final List<Span> spans = new ArrayList<>();
    public float marginTopPx = 0f;
    public float marginBottomPx = 0f;
    public Float lineHeightPx = null;
    public Float lineHeightMult = null;
    public BufferedImage image = null;
    public Integer imgAttrWidthPx = null;
    public Integer imgAttrHeightPx = null;
    public transient AttributedString attr;
}
