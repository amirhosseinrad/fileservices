package ir.ipaam.fileservice.application.service.htmltopdf.css;

import ir.ipaam.fileservice.application.service.htmltopdf.model.Style;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.awt.Color;
import java.util.*;

public class CssEngine {

    private static final String UA_CSS = String.join(" ",
            "p{margin-top:1em;margin-bottom:1em;}",
            "h1{margin-top:0.67em;margin-bottom:0.67em;}",
            "h2{margin-top:0.83em;margin-bottom:0.83em;}",
            "h3{margin-top:1.00em;margin-bottom:1.00em;}"
    );

    private final List<CssRule> rules;

    private CssEngine(List<CssRule> rules) {
        this.rules = rules;
    }

    public static CssEngine from(Document doc) {
        List<CssRule> out = new ArrayList<>();
        out.addAll(parseCss(UA_CSS, 0));
        int order = out.size();
        NodeList styles = doc.getElementsByTagName("style");
        for (int i = 0; i < styles.getLength(); i++) {
            Node n = styles.item(i);
            String css = n.getTextContent();
            if (css == null || css.isBlank()) continue;
            out.addAll(parseCss(css, order));
            order = out.size();
        }
        return new CssEngine(out);
    }

    public Style apply(Element el, Style inherited) {
        Style s = inherited.copy();
        List<CssRule> matched = new ArrayList<>();
        for (CssRule rule : rules) {
            if (rule.matches(el)) matched.add(rule);
        }
        matched.sort(Comparator.comparingInt((CssRule r) -> r.specificity).thenComparingInt(r -> r.order));
        for (CssRule rule : matched) {
            applyDecls(rule.decls, s, inherited);
        }

        String tag = el.getTagName().toLowerCase(Locale.ROOT);
        if (tag.equals("b") || tag.equals("strong")) s.bold = true;
        if (tag.equals("i") || tag.equals("em")) s.italic = true;
        if (tag.equals("u")) s.underline = true;
        if (s.textAlign == null) s.textAlign = "right";

        return s;
    }

    private static List<CssRule> parseCss(String css, int startOrder) {
        List<CssRule> out = new ArrayList<>();
        LinePointer line = new LinePointer(1);
        int order = startOrder;
        int len = css.length();
        int i = 0;

        while (true) {
            i = skipWhitespaceAndComments(css, i, line);
            if (i >= len) {
                break;
            }

            int selectorStartLine = line.value;
            StringBuilder selectorBuf = new StringBuilder();
            boolean foundBrace = false;
            while (i < len) {
                char c = css.charAt(i);
                if (isCommentStart(css, i)) {
                    i = skipComment(css, i, line);
                    continue;
                }
                if (c == '{') {
                    foundBrace = true;
                    i++;
                    break;
                }
                if (c == '}') {
                    throw new IllegalArgumentException("Malformed CSS at line " + line.value + ": unexpected '}'");
                }
                if (c == '\n') {
                    line.value++;
                }
                selectorBuf.append(c);
                i++;
            }

            if (!foundBrace) {
                if (selectorBuf.toString().trim().isEmpty()) {
                    break;
                }
                throw new IllegalArgumentException("Malformed CSS at line " + selectorStartLine + ": missing '{'");
            }

            String selectorText = selectorBuf.toString().trim();
            if (selectorText.isEmpty()) {
                throw new IllegalArgumentException("Malformed CSS at line " + selectorStartLine + ": empty selector");
            }

            int bodyStartLine = line.value;
            StringBuilder bodyBuf = new StringBuilder();
            boolean closed = false;
            while (i < len) {
                char c = css.charAt(i);
                if (isCommentStart(css, i)) {
                    i = skipComment(css, i, line);
                    continue;
                }
                if (c == '}') {
                    closed = true;
                    i++;
                    break;
                }
                if (c == '{') {
                    throw new IllegalArgumentException("Malformed CSS at line " + line.value + ": unexpected '{'");
                }
                if (c == '\n') {
                    line.value++;
                }
                bodyBuf.append(c);
                i++;
            }

            if (!closed) {
                throw new IllegalArgumentException("Malformed CSS at line " + line.value + ": missing '}'");
            }

            Map<String, String> decls = parseDeclarations(bodyBuf.toString(), bodyStartLine);
            if (decls.isEmpty()) {
                continue;
            }

            for (String sel : selectorText.split(",")) {
                String s = sel.trim();
                if (s.isEmpty()) continue;
                CssRule rule = parseSelector(s, decls, order++);
                if (rule != null) out.add(rule);
            }
        }
        return out;
    }

    private static Map<String, String> parseDeclarations(String body, int startLine) {
        Map<String, String> decls = new HashMap<>();
        LinePointer line = new LinePointer(startLine);
        int len = body.length();
        int i = 0;

        while (true) {
            i = skipWhitespaceAndComments(body, i, line);
            if (i >= len) {
                break;
            }

            int declLine = line.value;
            StringBuilder declBuf = new StringBuilder();
            while (i < len) {
                char c = body.charAt(i);
                if (isCommentStart(body, i)) {
                    i = skipComment(body, i, line);
                    continue;
                }
                if (c == ';') {
                    i++;
                    break;
                }
                if (c == '\n') {
                    line.value++;
                }
                declBuf.append(c);
                i++;
            }

            String decl = declBuf.toString().trim();
            if (decl.isEmpty()) {
                continue;
            }

            int colon = decl.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("Malformed CSS at line " + declLine + ": missing ':' in declaration");
            }

            String key = decl.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String val = decl.substring(colon + 1).replace("!important", "").trim().toLowerCase(Locale.ROOT);
            switch (key) {
                case "font-weight":
                case "font-style":
                case "font-size":
                case "color":
                case "text-align":
                case "line-height":
                case "margin-top":
                case "margin-bottom":
                case "margin":
                case "text-decoration":
                    decls.put(key, val);
                    break;
            }
        }

        return decls;
    }

    private static CssRule parseSelector(String s, Map<String, String> decls, int order) {
        String tag = null, id = null;
        Set<String> classes = new HashSet<>();
        String rest = s;

        if (rest.matches(".*\\s+.*") || rest.contains(">") || rest.contains("+") || rest.contains("~") || rest.contains(":")) {
            return null;
        }

        while (!rest.isEmpty()) {
            if (rest.startsWith("#")) {
                int end = nextSpecial(rest.substring(1));
                id = rest.substring(1, end + 1);
                rest = rest.substring(end + 1);
                continue;
            }
            if (rest.startsWith(".")) {
                int end = nextSpecial(rest.substring(1));
                classes.add(rest.substring(1, end + 1).toLowerCase(Locale.ROOT));
                rest = rest.substring(end + 1);
                continue;
            }
            int end = nextSpecial(rest);
            tag = rest.substring(0, end + 1).toLowerCase(Locale.ROOT);
            rest = rest.substring(end + 1);
        }

        int specificity = 0;
        if (id != null) specificity += 100;
        specificity += classes.size() * 10;
        if (tag != null && !tag.isBlank()) specificity += 1;
        return new CssRule(s, tag, id, classes, specificity, order, decls);
    }

    private static int nextSpecial(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '#' || c == '.' || c == ':' || c == ' ' || c == '>' || c == '+' || c == '~') {
                return i - 1;
            }
        }
        return s.length() - 1;
    }

    private static int skipWhitespaceAndComments(String css, int idx, LinePointer line) {
        int len = css.length();
        boolean advanced;
        do {
            advanced = false;
            while (idx < len) {
                char c = css.charAt(idx);
                if (Character.isWhitespace(c)) {
                    if (c == '\n') {
                        line.value++;
                    }
                    idx++;
                    advanced = true;
                } else {
                    break;
                }
            }
            if (idx < len - 1 && css.charAt(idx) == '/' && css.charAt(idx + 1) == '*') {
                idx = skipComment(css, idx, line);
                advanced = true;
            }
        } while (advanced);
        return idx;
    }

    private static boolean isCommentStart(String css, int idx) {
        return idx < css.length() - 1 && css.charAt(idx) == '/' && css.charAt(idx + 1) == '*';
    }

    private static int skipComment(String css, int idx, LinePointer line) {
        int len = css.length();
        int startLine = line.value;
        idx += 2; // skip /*
        while (idx < len) {
            char c = css.charAt(idx);
            if (c == '\n') {
                line.value++;
            }
            if (c == '*' && idx < len - 1 && css.charAt(idx + 1) == '/') {
                return idx + 2;
            }
            idx++;
        }
        throw new IllegalArgumentException("Malformed CSS at line " + startLine + ": unterminated comment");
    }

    private static final class LinePointer {
        int value;

        LinePointer(int value) {
            this.value = value;
        }
    }

    private static void applyDecls(Map<String, String> decls, Style s, Style parent) {
        for (Map.Entry<String, String> e : decls.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            switch (k) {
                case "font-weight": {
                    Integer w = parseFontWeight(v);
                    if (w != null) s.bold = (w >= 600) || "bold".equals(v);
                    break;
                }
                case "font-style":
                    s.italic = v.contains("italic") || v.contains("oblique");
                    break;
                case "font-size": {
                    Float px = parseFontSizePx(v, parent.fontSize);
                    if (px != null) s.fontSize = px;
                    break;
                }
                case "color": {
                    Color c = parseCssColor(v);
                    if (c != null) s.color = c;
                    break;
                }
                case "text-align":
                    switch (v) {
                        case "left":
                        case "right":
                        case "center":
                        case "justify":
                            s.textAlign = v;
                            break;
                    }
                    break;
                case "line-height": {
                    parseLineHeight(v, parent, s);
                    break;
                }
                case "margin-top": {
                    Float mt = parseLengthPx(v, parent.fontSize);
                    if (mt != null) s.marginTopPx = mt;
                    break;
                }
                case "margin-bottom": {
                    Float mb = parseLengthPx(v, parent.fontSize);
                    if (mb != null) s.marginBottomPx = mb;
                    break;
                }
                case "margin": {
                    float[] tb = parseMarginShorthand(v, parent.fontSize);
                    s.marginTopPx = tb[0];
                    s.marginBottomPx = tb[1];
                    break;
                }
                case "text-decoration": {
                    if (v.contains("underline")) s.underline = true;
                    break;
                }
                default:
                    break;
            }
        }
    }

    private static void parseLineHeight(String v, Style parent, Style s) {
        if ("normal".equals(v)) {
            s.lineHeightPx = null;
            s.lineHeightMult = null;
            return;
        }
        try {
            float m = Float.parseFloat(v);
            s.lineHeightPx = null;
            s.lineHeightMult = m;
            return;
        } catch (NumberFormatException ignore) {
        }
        Float px = parseLengthPx(v, parent.fontSize);
        if (px != null) {
            s.lineHeightPx = px;
            s.lineHeightMult = null;
        }
    }

    private static Float parseLengthPx(String v, float parentPx) {
        if (v == null) return null;
        v = v.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || "auto".equals(v) || "inherit".equals(v) || "initial".equals(v)) return null;
        try {
            if (v.endsWith("px")) return Float.parseFloat(v.substring(0, v.length() - 2).trim());
            if (v.endsWith("rem")) return Float.parseFloat(v.substring(0, v.length() - 3).trim()) * 16f;
            if (v.endsWith("em")) return Float.parseFloat(v.substring(0, v.length() - 2).trim()) * parentPx;
            if (v.endsWith("%")) return parentPx * (Float.parseFloat(v.substring(0, v.length() - 1).trim()) / 100f);
            return Float.parseFloat(v);
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    private static float[] parseMarginShorthand(String v, float parentPx) {
        String[] parts = v.trim().replaceAll("\\s+", " ").split(" ");
        Float p0 = parts.length >= 1 ? parseLengthPx(parts[0], parentPx) : null;
        Float p1 = parts.length >= 2 ? parseLengthPx(parts[1], parentPx) : null;
        Float p2 = parts.length >= 3 ? parseLengthPx(parts[2], parentPx) : null;
        Float p3 = parts.length >= 4 ? parseLengthPx(parts[3], parentPx) : null;

        float top;
        float bottom;
        if (parts.length == 1) {
            top = nz(p0);
            bottom = nz(p0);
        } else if (parts.length == 2) {
            top = nz(p0);
            bottom = nz(p0);
        } else if (parts.length == 3) {
            top = nz(p0);
            bottom = nz(p2);
        } else {
            top = nz(p0);
            bottom = nz(p2);
        }
        return new float[]{top, bottom};
    }

    private static float nz(Float f) {
        return f != null ? f : 0f;
    }

    private static Integer parseFontWeight(String v) {
        if ("normal".equals(v)) return 400;
        if ("bold".equals(v)) return 700;
        try {
            return Integer.parseInt(v);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static Float parseFontSizePx(String v, float parentPx) {
        try {
            if (v.endsWith("px")) return Float.parseFloat(v.replace("px", "").trim());
            if (v.endsWith("rem")) return Float.parseFloat(v.replace("rem", "").trim()) * 16f;
            if (v.endsWith("em")) return Float.parseFloat(v.replace("em", "").trim()) * parentPx;
            return Float.parseFloat(v);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static Color parseCssColor(String v) {
        try {
            if (v.startsWith("#")) {
                String hex = v.substring(1);
                if (hex.length() == 3) {
                    int r = Integer.parseInt(hex.substring(0, 1) + hex.substring(0, 1), 16);
                    int g = Integer.parseInt(hex.substring(1, 2) + hex.substring(1, 2), 16);
                    int b = Integer.parseInt(hex.substring(2, 3) + hex.substring(2, 3), 16);
                    return new Color(r, g, b);
                } else if (hex.length() == 6) {
                    return new Color(Integer.parseInt(hex, 16));
                }
            } else {
                return switch (v) {
                    case "black" -> Color.BLACK;
                    case "white" -> Color.WHITE;
                    case "red" -> new Color(0xFF0000);
                    case "green" -> new Color(0x00AA00);
                    case "blue" -> new Color(0x0000FF);
                    case "gray", "grey" -> new Color(0x808080);
                    default -> null;
                };
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static class CssRule {
        final String rawSelector;
        final String tag;
        final String id;
        final Set<String> classes;
        final int specificity;
        final int order;
        final Map<String, String> decls;

        CssRule(String rawSelector, String tag, String id, Set<String> classes, int specificity, int order, Map<String, String> decls) {
            this.rawSelector = rawSelector;
            this.tag = tag;
            this.id = id;
            this.classes = classes;
            this.specificity = specificity;
            this.order = order;
            this.decls = decls;
        }

        boolean matches(Element el) {
            if (tag != null && !tag.equalsIgnoreCase(el.getTagName())) return false;
            if (id != null && !id.equals(el.getAttribute("id"))) return false;
            if (!classes.isEmpty()) {
                String cls = el.getAttribute("class");
                if (cls == null || cls.isBlank()) return false;
                Set<String> elClasses = new HashSet<>(Arrays.asList(cls.toLowerCase(Locale.ROOT).trim().split("\\s+")));
                if (!elClasses.containsAll(classes)) return false;
            }
            return true;
        }
    }
}
