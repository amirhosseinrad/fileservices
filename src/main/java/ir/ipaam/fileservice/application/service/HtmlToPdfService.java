package ir.ipaam.fileservice.application.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.util.*;
import java.util.List;

/**
 * Minimal HTML (XHTML) -> PDF converter with zero external libraries.
 * - Persian text via Java2D text shaping and Bidi support
 * - Supports inline styles: font-size (px), color (#RRGGBB), font-weight, font-style, text-align
 * - Supported tags: <div>, <p>, <span>, <b>, <i>, <br>, <h1>-<h3>
 * - Renders to A4 pages at 150 DPI by default; auto-paginates
 *
 * IMPORTANT: This rasterizes text into page images and embeds those into PDF pages.
 * This avoids manual font embedding/ToUnicode/CMap complexity, while producing a printable PDF.
 */
public class HtmlToPdfService {

    // ---- Page & layout defaults ----
    private static final double DPI = 150.0;                  // Image render DPI
    private static final int A4_WIDTH_PT = 595;               // PDF points
    private static final int A4_HEIGHT_PT = 842;              // PDF points
    private static final int PAGE_WIDTH_IMG = ptToImg(A4_WIDTH_PT);
    private static final int PAGE_HEIGHT_IMG = ptToImg(A4_HEIGHT_PT);

    private static final int PAGE_WIDTH = A4_WIDTH_PT;        // PDF points
    private static final int PAGE_HEIGHT = A4_HEIGHT_PT;

    private static final int MARGIN_LEFT_PT = 36;             // 0.5 inch
    private static final int MARGIN_RIGHT_PT = 36;
    private static final int MARGIN_TOP_PT = 36;
    private static final int MARGIN_BOTTOM_PT = 36;

    private static final int MARGIN_LEFT_IMG = ptToImg(MARGIN_LEFT_PT);
    private static final int MARGIN_RIGHT_IMG = ptToImg(MARGIN_RIGHT_PT);
    private static final int MARGIN_TOP_IMG = ptToImg(MARGIN_TOP_PT);
    private static final int MARGIN_BOTTOM_IMG = ptToImg(MARGIN_BOTTOM_PT);

    private static final int CONTENT_W_IMG = PAGE_WIDTH_IMG - MARGIN_LEFT_IMG - MARGIN_RIGHT_IMG;

    // ---- Font resources (class-path) ----
    private static final String FONT_REGULAR = "/fonts/Vazirmatn-Regular.ttf";
    private static final String FONT_BOLD    = "/fonts/Vazirmatn-Bold.ttf";
    private static final String FONT_ITALIC  = "/fonts/Vazirmatn-Italic.ttf";

    private final Font fontRegular;
    private final Font fontBold;
    private final Font fontItalic;

    public HtmlToPdfService() {
        this.fontRegular = loadFontOrFallback(FONT_REGULAR, Font.PLAIN);
        this.fontBold    = loadFontOrFallback(FONT_BOLD, Font.BOLD);
        this.fontItalic  = loadFontOrFallback(FONT_ITALIC, Font.ITALIC);
    }

    // Public API: convert XHTML string to PDF bytes
    public byte[] convertXhtmlToPdf(String xhtml) {
        xhtml = sanitizeEntities(xhtml);
        xhtml = fixUnclosedPTags(xhtml);

        try {
            // Parse and extract renderable blocks
            Document doc = parseXhtml(xhtml);
            List<Block> blocks = extractBlocks(doc.getDocumentElement());

            // Render XHTML blocks to page images
            List<BufferedImage> pages = renderBlocksToPages(blocks);

            // Build final PDF from images
            return buildPdfFromJpegs(pages);

        } catch (Exception e) {
            throw new IllegalStateException("Failed to convert XHTML to PDF: " + e.getMessage(), e);
        }
    }

    // Overload: stream input
    public byte[] convertXhtmlToPdf(InputStream in) {
        try (in) {
            String xhtml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return convertXhtmlToPdf(xhtml);
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading XHTML: " + e.getMessage(), e);
        }
    }

    // ---------- Parsing ----------

    private Document parseXhtml(String xhtml) {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setValidating(false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            var db = dbf.newDocumentBuilder();
            return db.parse(new InputSource(new StringReader(xhtml)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Input must be well-formed XHTML. " + e.getMessage(), e);
        }
    }

    // Extracts linear list of "blocks" (paragraph-like) with inline spans and styles
    private List<Block> extractBlocks(Element root) {
        List<Block> out = new ArrayList<>();
        walk(root, new Style(), out);
        return out;
    }

    // DFS through nodes; build Block list
    private void walk(Node node, Style inherited, List<Block> out) {
        if (node.getNodeType() == Node.TEXT_NODE) {
            String txt = normalizeSpaces(node.getTextContent());
            if (!txt.isEmpty()) {
                // Attach to last block or create a default left-aligned block
                if (out.isEmpty()) {
                    Block b = new Block();
                    b.align = "right"; // Persian default
                    out.add(b);
                }
                out.get(out.size() - 1).spans.add(new Span(txt, inherited.copy()));
            }
            return;
        }
        if (node.getNodeType() != Node.ELEMENT_NODE) {
            // ignore comments, etc.
            NodeList ch = node.getChildNodes();
            for (int i = 0; i < ch.getLength(); i++) walk(ch.item(i), inherited, out);
            return;
        }

        Element el = (Element) node;
        String tag = el.getTagName().toLowerCase(Locale.ROOT);

        // Compute local style
        Style current = inherited.copy();
        applyInlineStyle(el, current);

        switch (tag) {
            case "div":
            case "p":
            case "h1":
            case "h2":
            case "h3": {
                Block b = new Block();
                // headings: larger default font
                if (tag.equals("h1")) current.fontSize = Math.max(current.fontSize, 28f);
                if (tag.equals("h2")) current.fontSize = Math.max(current.fontSize, 22f);
                if (tag.equals("h3")) current.fontSize = Math.max(current.fontSize, 18f);
                b.align = current.textAlign != null ? current.textAlign : "right";
                // collect child inline spans
                collectInline(el, current, b.spans);
                // always terminate block
                out.add(b);
                break;
            }
            case "span":
            case "b":
            case "strong":
            case "i":
            case "em": {
                // inline container; attach to last block or create one
                if (out.isEmpty()) {
                    Block b = new Block();
                    b.align = current.textAlign != null ? current.textAlign : "right";
                    out.add(b);
                }
                // adjust style by tag semantics
                if (tag.equals("b") || tag.equals("strong")) current.bold = true;
                if (tag.equals("i") || tag.equals("em")) current.italic = true;

                List<Span> spans = out.get(out.size() - 1).spans;
                collectInline(el, current, spans);
                break;
            }
            case "br": {
                // force new block
                Block b = new Block();
                b.align = current.textAlign != null ? current.textAlign : "right";
                out.add(b);
                break;
            }
            default: {
                // Unknown tag: recurse
                NodeList ch = el.getChildNodes();
                for (int i = 0; i < ch.getLength(); i++) walk(ch.item(i), current, out);
            }
        }
    }

    private void collectInline(Element container, Style current, List<Span> spans) {
        NodeList ch = container.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() == Node.TEXT_NODE) {
                String t = normalizeSpaces(n.getTextContent());
                if (!t.isEmpty()) spans.add(new Span(t, current.copy()));
            } else if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element e = (Element) n;
                Style s2 = current.copy();
                applyInlineStyle(e, s2);
                String tag = e.getTagName().toLowerCase(Locale.ROOT);
                if (tag.equals("b") || tag.equals("strong")) s2.bold = true;
                if (tag.equals("i") || tag.equals("em")) s2.italic = true;

                collectInline(e, s2, spans);
            }
        }
    }

    private static String normalizeSpaces(String s) {
        return s == null ? "" : s.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    // Parse inline style="..."
    private void applyInlineStyle(Element el, Style s) {
        String style = el.getAttribute("style");
        if (style == null || style.isBlank()) return;

        String[] parts = style.split(";");
        for (String part : parts) {
            String[] kv = part.split(":", 2);
            if (kv.length != 2) continue;
            String key = kv[0].trim().toLowerCase(Locale.ROOT);
            String val = kv[1].trim().toLowerCase(Locale.ROOT);

            switch (key) {
                case "font-weight":
                    s.bold = val.contains("bold") || val.equals("700") || val.equals("800") || val.equals("900");
                    break;
                case "font-style":
                    s.italic = val.contains("italic") || val.contains("oblique");
                    break;
                case "font-size":
                    // supports px only
                    if (val.endsWith("px")) {
                        try { s.fontSize = Float.parseFloat(val.replace("px", "").trim()); } catch (Exception ignore) {}
                    }
                    break;
                case "color":
                    s.color = parseCssColor(val);
                    break;
                case "text-align":
                    if (val.equals("right") || val.equals("left") || val.equals("center")) s.textAlign = val;
                    break;
            }
        }
    }

    private static Color parseCssColor(String v) {
        try {
            if (v.startsWith("#")) {
                String hex = v.substring(1);
                if (hex.length() == 3) {
                    int r = Integer.parseInt(hex.substring(0,1)+hex.substring(0,1), 16);
                    int g = Integer.parseInt(hex.substring(1,2)+hex.substring(1,2), 16);
                    int b = Integer.parseInt(hex.substring(2,3)+hex.substring(2,3), 16);
                    return new Color(r, g, b);
                } else if (hex.length() == 6) {
                    return new Color(Integer.parseInt(hex, 16));
                }
            }
        } catch (Exception ignore) {}
        return Color.BLACK;
    }

    // ---------- Rendering to page images ----------

    private List<BufferedImage> renderBlocksToPages(List<Block> blocks) throws IOException {
        List<BufferedImage> pages = new ArrayList<>();
        int y = MARGIN_TOP_IMG;
        BufferedImage page = newPageImage();
        Graphics2D g = prepG(page);

        for (Block b : blocks) {
            // collapse consecutive spaces already done; now build attributed text
            List<Line> lines = layoutBlockToLines(g, b, CONTENT_W_IMG);
            int blockHeight = lines.stream().mapToInt(line -> line.height).sum();

            // page break if needed
            if (y + blockHeight > PAGE_HEIGHT_IMG - MARGIN_BOTTOM_IMG) {
                pages.add(page);
                page = newPageImage();
                g = prepG(page);
                y = MARGIN_TOP_IMG;
            }
            // draw lines with alignment
            for (Line line : lines) {
                int x = MARGIN_LEFT_IMG;
                if ("center".equalsIgnoreCase(b.align)) {
                    x = MARGIN_LEFT_IMG + (CONTENT_W_IMG - line.width) / 2;
                } else if ("right".equalsIgnoreCase(b.align)) {
                    x = PAGE_WIDTH_IMG - MARGIN_RIGHT_IMG - line.width;
                }
                if (line.layout != null) {
                    line.layout.draw(g, x, y + line.ascent);
                } else if (line.text != null) {
                    g.drawString(line.text, x, y + line.ascent);
                }
                y += line.height;
            }
            // paragraph spacing
            y += scalePxToImg(8);
        }

        // flush last page
        pages.add(page);
        g.dispose();
        return pages;
    }

    private static BufferedImage newPageImage() {
        BufferedImage img = new BufferedImage(PAGE_WIDTH_IMG, PAGE_HEIGHT_IMG, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, PAGE_WIDTH_IMG, PAGE_HEIGHT_IMG);
        g.dispose();
        return img;
    }

    private Graphics2D prepG(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        // High-quality text rendering
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    // Layout a block into wrapped lines using Java line breaking (RTL supported)
    private List<Line> layoutBlockToLines(Graphics2D g, Block b, int maxWidth) {
        // Build one AttributedString across all spans
        StringBuilder sb = new StringBuilder();
        List<SpanRun> runs = new ArrayList<>();

        for (Span sp : b.spans) {
            int start = sb.length();
            sb.append(sp.text);
            int end = sb.length();
            runs.add(new SpanRun(start, end, sp.style));
        }

        AttributedString attr = new AttributedString(sb.toString());
        for (SpanRun r : runs) {
            Font base = pickFont(r.style);
            // CSS pixel → image px (same here)
            float px = Math.max(10f, r.style.fontSize);
            Font f2 = base.deriveFont(px);
            attr.addAttribute(TextAttribute.FONT, f2, r.start, r.end);
            attr.addAttribute(TextAttribute.FOREGROUND, r.style.color, r.start, r.end);
            // Let Java handle bidi/RTL automatically via characters
        }

        AttributedCharacterIterator it = attr.getIterator();
        FontRenderContext frc = g.getFontRenderContext();
        if (sb.length() == 0) {
            return Collections.emptyList();
        }
        LineBreakMeasurer lbm = new LineBreakMeasurer(it, frc);

        List<Line> out = new ArrayList<>();
        int paragraphStart = it.getBeginIndex();
        int paragraphEnd = it.getEndIndex();
        lbm.setPosition(paragraphStart);
        while (lbm.getPosition() < paragraphEnd) {
            TextLayout layout = lbm.nextLayout(maxWidth);
            // Get metrics
            int ascent = (int) Math.ceil(layout.getAscent());
            int descent = (int) Math.ceil(layout.getDescent() + layout.getLeading());
            int height = ascent + descent;
            int width = (int) Math.ceil(layout.getVisibleAdvance());

            // Extract the glyph text for drawString fallback: we’ll store the substring.
            int lineStart = lbm.getPosition() - layout.getCharacterCount();
            int lineEnd = lbm.getPosition();
            it.setIndex(lineStart);
            StringBuilder lineText = new StringBuilder();
            for (int i = lineStart; i < lineEnd; i++) {
                lineText.append(it.current());
                it.next();
            }

            out.add(new Line(lineText.toString(), width, height, ascent));
        }

        // Apply the final style color to Graphics2D for drawing — but lines may vary by spans.
        // Simplification: we draw multi-color lines by slicing them run-by-run:
        // Instead of the above drawString per line, we’ll override and draw colored segments:
        // For simplicity in this first version, keep single color per block if mixed colors are rare.
        // If you need multi-color per line, we can upgrade to use TextLayout.draw(g, x, y).
        // (That requires a different approach to alignment measurement.)
        //
        // To keep alignment + colors correct, we’ll do this:
        // - Recompute width via TextLayout for each line (done)
        // - Draw via TextLayout to preserve span colors: we need a 2nd pass right below
        return recolorLinesWithLayouts(attr, frc, maxWidth, b.align);
    }

    // Re-run the layout to produce drawable TextLayouts with correct colors
    private List<Line> recolorLinesWithLayouts(AttributedString attr, FontRenderContext frc, int maxWidth, String align) {
        List<Line> out = new ArrayList<>();
        AttributedCharacterIterator it = attr.getIterator();
        LineBreakMeasurer lbm = new LineBreakMeasurer(it, frc);
        int paragraphStart = it.getBeginIndex();
        int paragraphEnd = it.getEndIndex();
        lbm.setPosition(paragraphStart);
        while (lbm.getPosition() < paragraphEnd) {
            TextLayout layout = lbm.nextLayout(maxWidth);
            int ascent = (int) Math.ceil(layout.getAscent());
            int descent = (int) Math.ceil(layout.getDescent() + layout.getLeading());
            int height = ascent + descent;
            int width = (int) Math.ceil(layout.getVisibleAdvance());
            out.add(new Line(layout, width, height, ascent, align));
        }
        return out;
    }

    private Font pickFont(Style s) {
        if (s.bold && s.italic) {
            // If you have BoldItalic file, swap here; otherwise fake with deriveFont
            return fontBold.deriveFont(Font.ITALIC);
        } else if (s.bold) {
            return fontBold;
        } else if (s.italic) {
            return fontItalic;
        } else {
            return fontRegular;
        }
    }

    private static int ptToImg(int pt) {
        return (int) Math.round(pt * (DPI / 72.0));
    }

    private static int scalePxToImg(int px) { return px; }

    // ---------- PDF building (images -> pages) ----------

    private byte[] buildPdfFromJpegs(List<BufferedImage> pages) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PDDocument doc = new PDDocument();

        for (BufferedImage img : pages) {
            PDPage page = new PDPage(new PDRectangle(PAGE_WIDTH, PAGE_HEIGHT));
            doc.addPage(page);

            PDImageXObject pdImage = LosslessFactory.createFromImage(doc, img);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(pdImage, 0, 0, PAGE_WIDTH, PAGE_HEIGHT);
            }
        }

        doc.save(out);
        doc.close();
        return out.toByteArray();
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
    }

    // ---------- Font loading ----------

    private static Font loadFontOrFallback(String path, int style) {
        try (InputStream in = HtmlToPdfService.class.getResourceAsStream(path)) {
            if (in != null) {
                Font base = Font.createFont(Font.TRUETYPE_FONT, in);
                // Derive a reasonable default size; actual size is set per span
                return base.deriveFont(style, 16f);
            }
        } catch (Exception ignore) {}
        // Fallback to logical font (should still shape via AWT on systems with Arabic support)
        return new Font("SansSerif", style, 16);
    }

    // ---------- Data structures ----------

    private static class Style {
        boolean bold = false;
        boolean italic = false;
        float fontSize = 16f;
        Color color = Color.BLACK;
        String textAlign = null; // "right", "left", "center"

        Style copy() {
            Style s = new Style();
            s.bold = this.bold;
            s.italic = this.italic;
            s.fontSize = this.fontSize;
            s.color = this.color;
            s.textAlign = this.textAlign;
            return s;
        }
    }

    private static class Span {
        final String text;
        final Style style;
        Span(String t, Style s) { this.text = t; this.style = s; }
    }

    private static class Block {
        String align = "right"; // default for Persian
        List<Span> spans = new ArrayList<>();
    }

    private static class SpanRun {
        final int start, end;
        final Style style;
        SpanRun(int s, int e, Style st) { start = s; end = e; style = st; }
    }

    private static class Line {
        final String text;           // used in simple drawString mode
        final int width, height, ascent;
        final TextLayout layout;     // used for colored layouts
        final String align;

        Line(String text, int width, int height, int ascent) {
            this.text = text; this.width = width; this.height = height; this.ascent = ascent;
            this.layout = null; this.align = "right";
        }
        Line(TextLayout layout, int width, int height, int ascent, String align) {
            this.text = null; this.width = width; this.height = height; this.ascent = ascent;
            this.layout = layout; this.align = align == null ? "right" : align;
        }
    }

    public static String mergeHtmlAndCss(String htmlContent, String cssContent) {
        if (htmlContent == null || htmlContent.isBlank()) {
            throw new IllegalArgumentException("HTML content is empty");
        }
        if (cssContent == null || cssContent.isBlank()) {
            return htmlContent; // no CSS provided
        }

        // If <head> exists, insert <style> there; otherwise, add one
        if (htmlContent.contains("<head")) {
            return htmlContent.replaceFirst("(?i)(<head[^>]*>)",
                    "$1\n<style>\n" + cssContent + "\n</style>\n");
        } else if (htmlContent.contains("<html")) {
            return htmlContent.replaceFirst("(?i)(<html[^>]*>)",
                    "$1\n<head><style>\n" + cssContent + "\n</style></head>\n");
        } else {
            // fallback
            return "<html><head><style>\n" + cssContent + "\n</style></head><body>\n"
                    + htmlContent + "\n</body></html>";
        }
    }
    private static String sanitizeEntities(String html) {
        if (html == null) return "";
        // Replace common HTML named entities with XML-safe equivalents
        return html
                .replaceAll("&nbsp;", "&#160;")
                .replaceAll("&lt;", "&#60;")
                .replaceAll("&gt;", "&#62;")
                .replaceAll("&quot;", "&#34;")
                .replaceAll("&apos;", "&#39;")
                // replace stray ampersands that are not part of an entity
                .replaceAll("&(?!#?[a-zA-Z0-9]+;)", "&amp;");
    }

    private static String fixUnclosedPTags(String html) {
        // Close <p> tags that are opened but not properly closed before another <p> or block tag
        return html
                .replaceAll("(?i)<p([^>]*)>(.*?)((?=<p)|(?=<div)|(?=<table)|(?=<body)|(?=<html)|(?=$))", "<p$1>$2</p>");
    }


}
