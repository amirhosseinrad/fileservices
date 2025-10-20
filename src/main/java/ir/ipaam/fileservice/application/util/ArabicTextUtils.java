package ir.ipaam.fileservice.application.util;

import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.ArabicShapingException;
import com.ibm.icu.text.Bidi;

public final class ArabicTextUtils {

    private ArabicTextUtils() {
    }

    public static String shapeArabicText(String html) {
        StringBuilder out = new StringBuilder();
        StringBuilder textBuffer = new StringBuilder();
        boolean inTag = false;

        ArabicShaping shaper = new ArabicShaping(
                ArabicShaping.LETTERS_SHAPE | ArabicShaping.TEXT_DIRECTION_LOGICAL
        );

        for (char c : html.toCharArray()) {
            if (c == '<') {
                if (textBuffer.length() > 0) {
                    out.append(applyArabicShaping(textBuffer.toString(), shaper));
                    textBuffer.setLength(0);
                }
                inTag = true;
                out.append(c);
            } else if (c == '>') {
                inTag = false;
                out.append(c);
            } else if (inTag) {
                out.append(c);
            } else {
                textBuffer.append(c);
            }
        }

        if (textBuffer.length() > 0) {
            out.append(applyArabicShaping(textBuffer.toString(), shaper));
        }

        return out.toString();
    }

    private static String applyArabicShaping(String text, ArabicShaping shaper) {
        try {
            String shaped = shaper.shape(text);
            Bidi bidi = new Bidi(shaped, Bidi.REORDER_INVERSE_LIKE_DIRECT);
            String reordered = bidi.writeReordered(Bidi.DO_MIRRORING);
            reordered = reordered.replace("گ", "\uE001")
                    .replace("چ", "\uE002")
                    .replace("پ", "\uE003")
                    .replace("ژ", "\uE004");

            return reordered;
        } catch (ArabicShapingException e) {
            return text;
        }
    }
}
