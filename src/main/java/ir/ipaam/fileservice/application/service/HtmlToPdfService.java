package ir.ipaam.fileservice.application.service;

import ir.ipaam.fileservice.application.service.htmltopdf.ResourceResolver;
import ir.ipaam.fileservice.application.service.htmltopdf.css.CssEngine;
import ir.ipaam.fileservice.application.service.htmltopdf.model.Block;
import ir.ipaam.fileservice.application.service.htmltopdf.model.Line;
import ir.ipaam.fileservice.application.service.htmltopdf.model.Span;
import ir.ipaam.fileservice.application.service.htmltopdf.model.SpanRun;
import ir.ipaam.fileservice.application.service.htmltopdf.model.Style;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.batik.ext.awt.image.GraphicsUtil.createGraphics;

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
@Service
public class HtmlToPdfService {

    // ---- Page & layout defaults ----
    private static final double RENDER_DPI = 300.0;
    private static final double LAYOUT_DPI = 150.0;
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
    private static final int PARAGRAPH_SPACING_IMG = scalePxToImg(8);

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

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*([A-Za-z0-9_\\.]+)(?:\\s*\\|\\s*(raw))?\\s*\\}\\}");

    public static String renderTemplate(String xhtml, Map<String, Object> model) {
        if (xhtml == null) return "";
        Matcher m = PLACEHOLDER.matcher(xhtml);
        StringBuffer out = new StringBuffer(xhtml.length());
        while (m.find()) {
            String key = m.group(1);
            boolean raw = "raw".equalsIgnoreCase(m.group(2));
            Object val = lookup(model, key);           // dot-notation aware
            String s = val == null ? "" : String.valueOf(val);
            // normalize digits to Persian if you want consistent numerals:
            s = toPersianDigits(s);
            // escape unless |raw was used
            m.appendReplacement(out, raw ? Matcher.quoteReplacement(s)
                    : Matcher.quoteReplacement(htmlEscape(s)));
        }
        m.appendTail(out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static Object lookup(Map<String, Object> root, String path) {
        if (root == null || path == null) return null;
        Object cur = root;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(part);
            if (cur == null) return null;
        }
        return cur;
    }

    private static String htmlEscape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\''-> b.append("&#39;");
                default  -> b.append(c);
            }
        }
        return b.toString();
    }

    private static String stripUtf8Bom(String s) {
        return (s != null && s.startsWith("\uFEFF")) ? s.substring(1) : s;
    }


    public byte[] convertXhtmlToPdf(InputStream htmlIn, InputStream cssIn, Map<String, Object> model, ResourceResolver rr) {
        Objects.requireNonNull(htmlIn, "htmlIn");
        Objects.requireNonNull(cssIn, "cssIn");
        Objects.requireNonNull(rr, "resolver");
        try (htmlIn; cssIn) {
            String html = new String(htmlIn.readAllBytes(), StandardCharsets.UTF_8);
            String css  = new String(cssIn.readAllBytes(),  StandardCharsets.UTF_8);
            html = stripUtf8Bom(html);
            css  = stripUtf8Bom(css);

            String merged   = mergeHtmlAndCss(html, css);
            String rendered = (model == null || model.isEmpty()) ? merged : renderTemplate(merged, model);

            return convertXhtmlToPdf(rendered, rr);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed reading HTML/CSS", e);
        }
    }

    public byte[] convertXhtmlToPdf(String xhtml, ResourceResolver rr) throws IOException {
        xhtml = sanitizeEntities(xhtml);
        xhtml = fixUnclosedPTags(xhtml);

        try {
            Document doc = parseXhtml(xhtml);
            CssEngine css = CssEngine.from(doc);
            List<Block> blocks = extractBlocks(doc.getDocumentElement(), css, rr);
            List<BufferedImage> pages = renderBlocksToPages(blocks);
            return buildPdfFromJpegs(pages);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to convert XHTML to PDF: " + e.getMessage(), e);
        }
    }

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

    private List<Block> extractBlocks(Element root, CssEngine css, ResourceResolver rr) {
        List<Block> out = new ArrayList<>();
        walk(root, new Style(), out, css, rr);
        return out;
    }

    private void walk(Node node, Style inherited, List<Block> out, CssEngine css, ResourceResolver rr) {
        if (node.getNodeType() == Node.TEXT_NODE) {
            String txt = normalizeSpaces(node.getTextContent());
            if (!txt.isEmpty()) {
                if (out.isEmpty()) {
                    Block b = new Block();
                    b.align = "right";
                    out.add(b);
                }
                out.get(out.size() - 1).spans.add(new Span(txt, inherited.copy()));
            }
            return;
        }
        if (node.getNodeType() != Node.ELEMENT_NODE) {
            NodeList ch = node.getChildNodes();
            for (int i = 0; i < ch.getLength(); i++) walk(ch.item(i), inherited, out, css, rr);
            return;
        }

        Element el = (Element) node;
        String tag = el.getTagName().toLowerCase(Locale.ROOT);

        // Compute style from CSS (no inline)
        Style current = css.apply(el, inherited);
        switch (tag) {
            case "img": {
                String src = el.getAttribute("src");
                if (src != null && !src.isBlank()) {
                    try (InputStream in = openImage(src, rr)) {
                        BufferedImage bi = ImageIO.read(in);
                        if (bi != null) {
                            Block b = new Block();
                            b.align = inherited.textAlign != null ? inherited.textAlign : "right";
                            b.image = bi;

                            // read optional width/height *attributes* (numbers or like "140px")
                            String w = el.getAttribute("width");
                            String h = el.getAttribute("height");
                            if (w != null && !w.isBlank()) b.imgAttrWidthPx  = parseIntPxAttr(w);
                            if (h != null && !h.isBlank()) b.imgAttrHeightPx = parseIntPxAttr(h);

                            // allow vertical spacing via CSS margins from current style
                            b.marginTopPx = inherited.marginTopPx;
                            b.marginBottomPx = inherited.marginBottomPx;

                            out.add(b);
                        }
                    } catch (IOException ignore) {
                        // you could log: image missing; silently skip to keep rendering robust
                    }
                }
                break;
            }
            // ---- real block elements -> 1 block each ----
            case "p":
            case "h1":
            case "h2":
            case "h3": {
                // bump heading font-size BEFORE collecting spans
                if (tag.equals("h1")) current.fontSize = Math.max(current.fontSize, 28f);
                if (tag.equals("h2")) current.fontSize = Math.max(current.fontSize, 22f);
                if (tag.equals("h3")) current.fontSize = Math.max(current.fontSize, 18f);

                Block b = new Block();
                b.align = current.textAlign != null ? current.textAlign : "right";
                b.marginTopPx = current.marginTopPx;
                b.marginBottomPx = current.marginBottomPx;
                b.lineHeightPx = current.lineHeightPx;
                b.lineHeightMult = current.lineHeightMult;
                collectInline(el, current, b.spans, css);  // only inline children of THIS block element
                out.add(b);
                break;
            }

            // ---- <br/> should create a visible blank line ----
            case "br": {
                Block b = new Block();
                b.align = current.textAlign != null ? current.textAlign : "right";
                // use current line-height (or 1.2× font size fallback) as vertical step
                if (current.lineHeightPx != null) {
                    b.marginTopPx = current.lineHeightPx;
                } else if (current.lineHeightMult != null) {
                    b.marginTopPx = current.fontSize * current.lineHeightMult;
                } else {
                    b.marginTopPx = current.fontSize * 1.2f;
                }
                // no spans -> just vertical space
                out.add(b);
                break;
            }

            // ---- containers: just recurse; DO NOT flatten children into one block ----
            case "div":
            case "body":
            case "thead":
            case "tbody":
            case "tfoot":
            case "tr":
            case "td":
            case "th":
            case "table": {
                NodeList ch = el.getChildNodes();
                for (int i = 0; i < ch.getLength(); i++) walk(ch.item(i), current, out, css, rr);
                break;
            }

            // ---- inline wrappers -> keep adding into the current paragraph ----
            case "span":
            case "b":
            case "strong":
            case "i":
            case "em": {
                if (out.isEmpty()) {
                    Block b = new Block();
                    b.align = current.textAlign != null ? current.textAlign : "right";
                    out.add(b);
                }
                List<Span> spans = out.get(out.size() - 1).spans;
                collectInline(el, current, spans, css);
                break;
            }

            // ---- ignored head-y tags ----
            case "style":
            case "script":
            case "head":
            case "title":
                return;

            // ---- default: container-ish; recurse ----
            default: {
                NodeList ch = el.getChildNodes();
                for (int i = 0; i < ch.getLength(); i++) walk(ch.item(i), current, out, css, rr);
            }
        }

    }

    private static int parseIntPxAttr(String v) {
        v = v.trim().toLowerCase(Locale.ROOT);
        if (v.endsWith("px")) v = v.substring(0, v.length()-2);
        return Integer.parseInt(v.replaceAll("[^0-9]", ""));
    }

    private static InputStream openImage(String src, ResourceResolver rr) throws IOException {
        src = src.trim();
        if (src.startsWith("data:image/")) {
            // basic data URI support (optional)
            int comma = src.indexOf(',');
            String base64 = comma >= 0 ? src.substring(comma + 1) : "";
            byte[] bytes = Base64.getDecoder().decode(base64);
            return new ByteArrayInputStream(bytes);
        }
        return rr.open(src.startsWith("./") ? src.substring(2) : src);
    }


    private void collectInline(Element container, Style current, List<Span> spans, CssEngine css) {
        NodeList ch = container.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() == Node.TEXT_NODE) {
                String t = normalizeSpaces(n.getTextContent());
                if (!t.isEmpty()) spans.add(new Span(t, current.copy()));
            } else if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element e = (Element) n;
                Style s2 = css.apply(e, current);
                collectInline(e, s2, spans, css);
            }
        }
    }


    private static String normalizeSpaces(String s) {
        return s == null ? "" : s.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private List<BufferedImage> renderBlocksToPages(List<Block> blocks) throws IOException {

        BufferedImage headerImage = null;
        BufferedImage footerImage = null;
        try (InputStream in = HtmlToPdfService.class.getResourceAsStream("/morabehe/images/logo.png")) {
            if (in != null) headerImage = ImageIO.read(in);
        }
        try (InputStream in = HtmlToPdfService.class.getResourceAsStream("/morabehe/images/sign.png")) {
            if (in != null) footerImage = ImageIO.read(in);
        }

        // === Layout constants ===
        final int HEADER_HEIGHT = headerImage != null ? headerImage.getHeight() + 40 : 120;
        final int FOOTER_HEIGHT = footerImage != null ? footerImage.getHeight() + 60 : 140;

        int pageIndex = 0;
        int y = MARGIN_TOP_IMG + 40;

        // Create first page
        BufferedImage page = new BufferedImage(PAGE_WIDTH_IMG, PAGE_HEIGHT_IMG, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = createGraphics(page);
        drawHeaderAndFooter(g, headerImage, footerImage, pageIndex);
        boolean pageHasContent = false;

        List<BufferedImage> pages = new ArrayList<>();

        List<List<Line>> blockLines = new ArrayList<>(blocks.size());
        List<Integer> blockHeights = new ArrayList<>(blocks.size());
        for (Block b : blocks) {
            if (b.image != null) {
                blockLines.add(Collections.emptyList());
                int[] wh = measureImageDisplayWH(b.image.getWidth(), b.image.getHeight(),
                        b.imgAttrWidthPx, b.imgAttrHeightPx, CONTENT_W_IMG);
                blockHeights.add(wh[1]); // display height
            } else {
                List<Line> lines = layoutBlockToLines(g, b, CONTENT_W_IMG);
                blockLines.add(lines);
                blockHeights.add(lines.stream().mapToInt(line -> line.height).sum());
            }
        }
        int maxContentBottom = PAGE_HEIGHT_IMG - MARGIN_BOTTOM_IMG;

        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            List<Line> lines = blockLines.get(i);
            int blockHeight = blockHeights.get(i);

            int topM = Math.round(block.marginTopPx);
            int bottomM = Math.round(block.marginBottomPx);
            int required = topM + blockHeight + bottomM;

            if (y + required > maxContentBottom) {
                g.dispose();
                if (pageHasContent) pages.add(page);
                pageIndex++;
                page = newPageImage();
                g = prepG(page);
                drawHeaderAndFooter(g, headerImage, footerImage, pageIndex);
                y =  MARGIN_TOP_IMG + 40;
                pageHasContent = false;
            }

            // top margin (even if no lines)
            if (topM > 0) {
                y += topM;
                pageHasContent = true;
            }
            // draw lines (if any)
            if (block.image != null) {
                int[] wh = measureImageDisplayWH(block.image.getWidth(), block.image.getHeight(),
                        block.imgAttrWidthPx, block.imgAttrHeightPx, CONTENT_W_IMG);

                int drawW = wh[0];
                int drawH = wh[1];

                int x;
                if ("center".equalsIgnoreCase(block.align)) {
                    x = MARGIN_LEFT_IMG + (CONTENT_W_IMG - drawW) / 2;
                } else if ("left".equalsIgnoreCase(block.align)) {
                    x = MARGIN_LEFT_IMG;
                } else { // default right-align
                    x = PAGE_WIDTH_IMG - MARGIN_RIGHT_IMG - drawW;
                }

                g.drawImage(block.image, x, y, drawW, drawH, null);
                y += drawH;
                pageHasContent = true;
                continue;
            }

            for (Line line : lines) {
                if (y + line.height > maxContentBottom) {
                    g.dispose();
                    if (pageHasContent) pages.add(page);
                    page = newPageImage();
                    g = prepG(page);
                    y = MARGIN_TOP_IMG;
                    pageHasContent = false;
                }

                int x;
                if ("justify".equalsIgnoreCase(line.align) && line.layout != null) {
                    boolean ltr = line.layout.isLeftToRight();
                    x = ltr ? MARGIN_LEFT_IMG : PAGE_WIDTH_IMG - MARGIN_RIGHT_IMG - CONTENT_W_IMG;
                } else if ("center".equalsIgnoreCase(line.align)) {
                    x = MARGIN_LEFT_IMG + (CONTENT_W_IMG - line.width) / 2;
                } else if ("right".equalsIgnoreCase(line.align)) {
                    x = PAGE_WIDTH_IMG - MARGIN_RIGHT_IMG - line.width;
                } else {
                    x = MARGIN_LEFT_IMG;
                }

                int baseline = y + line.ascent;
                if (line.layout != null) line.layout.draw(g, x, baseline);
                else if (line.text != null) g.drawString(line.text, x, baseline);

                y += line.height;
                pageHasContent = true;
            }

            // bottom margin (even if no lines)
            if (bottomM > 0) {
                y += bottomM;
                pageHasContent = true;
            }
        }


        g.dispose();
        if (pageHasContent || pages.isEmpty()) {
            pages.add(page);
        }

        return pages;
    }


    private void drawHeaderAndFooter(Graphics2D g, BufferedImage headerImage, BufferedImage footerImage, int pageIndex) {
        // Background
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, PAGE_WIDTH_IMG, PAGE_HEIGHT_IMG);

        // ---- HEADER ----
        if (headerImage != null) {
            int scaledHeaderW = headerImage.getWidth() / 3;
            int scaledHeaderH = headerImage.getHeight() / 3;

            // align header to the right
            int xHeader = PAGE_WIDTH_IMG - MARGIN_RIGHT_IMG - scaledHeaderW;
            int yHeader = 20;

            g.drawImage(headerImage, xHeader, yHeader, scaledHeaderW, scaledHeaderH, null);

            // optional: title under header, aligned to right
            g.setColor(Color.BLACK);
/*            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            String title = "قرارداد مرابحه و تعهدنامه (طرح توربو وام)";
            int titleWidth = g.getFontMetrics().stringWidth(title);
            g.drawString(title, PAGE_WIDTH_IMG - MARGIN_RIGHT_IMG - titleWidth, yHeader + scaledHeaderH + 30);*/
        }

        // ---- FOOTER ----
        if (footerImage != null) {
            int scaledFooterW = footerImage.getWidth() / 2;
            int scaledFooterH = footerImage.getHeight() / 2;

            // align footer to the left
            int xFooter = MARGIN_LEFT_IMG;
            int yFooter = PAGE_HEIGHT_IMG - scaledFooterH - 30;

            g.drawImage(footerImage, xFooter, yFooter, scaledFooterW, scaledFooterH, null);

            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.drawString("مهر و امضا متقاضی / ضامنین / بانک",
                    PAGE_WIDTH_IMG - MARGIN_RIGHT_IMG - 220, PAGE_HEIGHT_IMG - 40);
        }

        // ---- PAGE NUMBER ----
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        g.setColor(Color.DARK_GRAY);
        g.drawString("صفحه " + (pageIndex + 1),
                PAGE_WIDTH_IMG / 2 - 20, PAGE_HEIGHT_IMG - 15);
    }



    private static int[] measureImageDisplayWH(int naturalW, int naturalH,
                                               Integer attrW, Integer attrH,
                                               int maxContentW) {
        double w = (attrW != null) ? attrW : naturalW;
        double h = (attrH != null) ? attrH : naturalH;
        // keep aspect if only one attr provided
        if (attrW != null && attrH == null) h = naturalH * (w / naturalW);
        if (attrH != null && attrW == null) w = naturalW * (h / naturalH);
        // fit to content width
        double scale = w > maxContentW ? (double) maxContentW / w : 1.0;
        int dispW = (int) Math.round(w * scale);
        int dispH = (int) Math.round(h * scale);
        return new int[]{ dispW, dispH };
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
            if (r.style.underline) {
                attr.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON, r.start, r.end);
            }

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
        return  recolorLinesWithLayouts(attr, frc, maxWidth, b);
    }

    private static boolean startsWithMarker(String s) {
        if (s == null) return false;
        String t = normalizeSpaces(s).trim();
        return t.startsWith("ماده") || t.startsWith("تبصره");
    }

    private static String toPersianDigits(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') {
                sb.append((char) ('\u06F0' + (ch - '0')));                 // 0–9 → ۰–۹
            } else if (ch >= '\u0660' && ch <= '\u0669') {
                sb.append((char) ('\u06F0' + (ch - '\u0660')));             // ٠–٩ → ۰–۹
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }


    private static String slice(AttributedCharacterIterator it, int start, int end) {
        StringBuilder sb = new StringBuilder();
        it.setIndex(start);
        for (int i = start; i < end; i++) { sb.append(it.current()); it.next(); }
        return sb.toString();
    }
    // Re-run the layout to produce drawable TextLayouts with correct colors
    private List<Line> recolorLinesWithLayouts(AttributedString attr,
                                               FontRenderContext frc,
                                               int maxWidth,
                                               Block block) {
        List<Line> out = new ArrayList<>();
        AttributedCharacterIterator it = attr.getIterator();
        LineBreakMeasurer lbm = new LineBreakMeasurer(it, frc);
        int paragraphStart = it.getBeginIndex();
        int paragraphEnd   = it.getEndIndex();
        lbm.setPosition(paragraphStart);

        while (lbm.getPosition() < paragraphEnd) {
            TextLayout layout = lbm.nextLayout(maxWidth);
            int ascent  = (int) Math.ceil(layout.getAscent());
            int descent = (int) Math.ceil(layout.getDescent() + layout.getLeading());
            int baseH   = ascent + descent;

            int height;
            if (block.lineHeightPx != null)      height = (int) Math.ceil(block.lineHeightPx);
            else if (block.lineHeightMult != null) height = (int) Math.ceil(baseH * block.lineHeightMult);
            else                                   height = baseH;

            int after = lbm.getPosition();
            int start = after - layout.getCharacterCount();
            String lineText = slice(it, start, after);
            boolean isLastLine = (after >= paragraphEnd);
            boolean isMarker   = startsWithMarker(lineText);

            int width;
            if ("justify".equalsIgnoreCase(block.align) && !isLastLine && !isMarker) {
                try { layout = layout.getJustifiedLayout(maxWidth); } catch (Exception ignore) {}
                width = maxWidth;
            } else {
                width = (int) Math.ceil(layout.getVisibleAdvance());
            }

            String align = isMarker ? "right" : (block.align == null ? "right" : block.align);
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
                double scale = 72.0 / RENDER_DPI; // use RENDER_DPI here, not LAYOUT_DPI
                float drawW = (float) (PAGE_WIDTH_IMG * (RENDER_DPI / LAYOUT_DPI) * scale);
                float drawH = (float) (PAGE_HEIGHT_IMG * (RENDER_DPI / LAYOUT_DPI) * scale);
                content.drawImage(pdImage, 0, 0, drawW, drawH);            }
        }

        doc.save(out);
        doc.close();
        return out.toByteArray();
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

    // Resolve from classpath folder like "pdf/" (so "./images/x.png" -> "pdf/images/x.png")
    public static ResourceResolver classpathResolver(String basePath) {
        String base = basePath == null ? "" : basePath.replace('\\','/').replaceAll("^/+", "").replaceAll("/+$","");
        return (src) -> {
            String norm = src.replace('\\','/').replaceAll("^\\./", "");
            String full = "/" + (base.isEmpty() ? "" : (base + "/")) + norm;
            InputStream in = HtmlToPdfService.class.getResourceAsStream(full);
            if (in == null) throw new FileNotFoundException("Not found on classpath: " + full);
            return in;
        };
    }

    // Resolve from filesystem directory (so "./images/x.png" resolved under that dir)
    public static ResourceResolver filesystemResolver(java.nio.file.Path baseDir) {
        return (src) -> {
            String norm = src.replace('\\','/').replaceAll("^\\./", "");
            java.nio.file.Path p = baseDir.resolve(norm).normalize();
            return java.nio.file.Files.newInputStream(p);
        };
    }

    public static ResourceResolver fileResolver(String baseUri) {
        return uri -> {
            try {
                URI resolvedUri = URI.create(uri);
                Path path;
                if (resolvedUri.isAbsolute() && "file".equals(resolvedUri.getScheme())) {
                    path = Paths.get(resolvedUri);
                } else {
                    // Relative path → resolve against base folder
                    path = Paths.get(URI.create(baseUri)).resolve(uri).normalize();
                }
                return Files.newInputStream(path);
            } catch (Exception e) {
                throw new RuntimeException("Could not resolve resource: " + uri, e);
            }
        };
    }






}
