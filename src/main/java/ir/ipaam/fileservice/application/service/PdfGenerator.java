package ir.ipaam.fileservice.application.service;

import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.ArabicShapingException;
import com.ibm.icu.text.Bidi;
import ir.ipaam.fileservice.domain.event.PdfCreatedEvent;
import org.apache.fop.apps.FOPException;
import org.apache.fop.apps.FOUserAgent;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.MimeConstants;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.regex.Pattern;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamSource;

@Service
public class PdfGenerator {

    private static final FopFactory FOP_FACTORY;

    static {
        try {
            FOP_FACTORY = FopFactory.newInstance(PdfGenerator.class.getResource("/").toURI());
        } catch (FOPException | java.net.URISyntaxException e) {
            throw new IllegalStateException("Unable to initialize FOP factory", e);
        }
    }

    public byte[] generate(String text) {
        return generate(new PdfCreatedEvent(null, text, null));
    }

    private static final Pattern RTL_PATTERN = Pattern.compile("[\\p{InArabic}]");

    private static final ArabicShaping ARABIC_SHAPING = new ArabicShaping(
            ArabicShaping.LETTERS_SHAPE | ArabicShaping.TEXT_DIRECTION_LOGICAL
    );

    public byte[] generate(PdfCreatedEvent event) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Path fontPath = copyFontToTempFile();
            try {
                String foContent = buildFoDocument(event, fontPath.toUri().toString());

                FOUserAgent foUserAgent = FOP_FACTORY.newFOUserAgent();
                Fop fop = FOP_FACTORY.newFop(MimeConstants.MIME_PDF, foUserAgent, out);

                TransformerFactory factory = TransformerFactory.newInstance();
                Transformer transformer = factory.newTransformer();
                Source src = new StreamSource(new StringReader(foContent));
                Result res = new SAXResult(fop.getDefaultHandler());
                transformer.transform(src, res);
            } finally {
                try {
                    Files.deleteIfExists(fontPath);
                } catch (IOException ignored) {
                    // If the temporary font cannot be deleted, let the JVM handle it via deleteOnExit.
                }
            }
            return out.toByteArray();
        } catch (IOException | TransformerException | FOPException e) {
            throw new RuntimeException("PDF generation failed", e);
        }
    }

    private Path copyFontToTempFile() throws IOException {
        try (InputStream fontStream = getClass().getResourceAsStream("/fonts/IranSans.ttf")) {
            if (fontStream == null) {
                throw new IllegalStateException("Font not found in resources: /fonts/IranSans.ttf");
            }
            Path tempFile = Files.createTempFile("iran-sans", ".ttf");
            Files.copy(fontStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            tempFile.toFile().deleteOnExit();
            return tempFile;
        }
    }

    private String buildFoDocument(PdfCreatedEvent event, String fontUri) {
        StringBuilder foBuilder = new StringBuilder();
        foBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        foBuilder.append("<fo:root xmlns:fo=\"http://www.w3.org/1999/XSL/Format\">\n");
        foBuilder.append("  <fo:layout-master-set>\n");
        foBuilder.append("    <fo:simple-page-master master-name=\"simple\" page-height=\"29.7cm\" page-width=\"21cm\" margin=\"2cm\">\n");
        foBuilder.append("      <fo:region-body margin-top=\"1cm\" margin-bottom=\"1cm\"/>\n");
        foBuilder.append("    </fo:simple-page-master>\n");
        foBuilder.append("  </fo:layout-master-set>\n");
        foBuilder.append("  <fo:declarations>\n");
        foBuilder.append("    <fo:font-face font-family=\"IranSans\" src=\"url('" + fontUri + "')\"/>\n");
        foBuilder.append("  </fo:declarations>\n");
        foBuilder.append("  <fo:page-sequence master-reference=\"simple\">\n");
        foBuilder.append("    <fo:flow flow-name=\"xsl-region-body\">\n");

        String[] lines = (event.getText() == null ? "" : event.getText()).split("\\\R", -1);
        for (String line : lines) {
            PreparedLine preparedLine = prepareLine(line);
            foBuilder.append("      <fo:block font-family=\"IranSans\" font-size=\"14pt\" line-height=\"16pt\"");
            if (preparedLine.rtl()) {
                foBuilder.append(" text-align=\"end\" writing-mode=\"rl-tb\" direction=\"rtl\"");
            } else {
                foBuilder.append(" text-align=\"start\"");
            }
            foBuilder.append(">");
            foBuilder.append(escapeXml(preparedLine.text()));
            foBuilder.append("</fo:block>\n");
        }

        foBuilder.append("    </fo:flow>\n");
        foBuilder.append("  </fo:page-sequence>\n");
        foBuilder.append("</fo:root>");
        return foBuilder.toString();
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder();
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '\"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.append(ch);
            }
        }
        return escaped.toString();
    }

    private PreparedLine prepareLine(String line) {
        if (line == null || line.isEmpty()) {
            return new PreparedLine("", false);
        }

        if (!containsRtlCharacters(line)) {
            return new PreparedLine(line, false);
        }

        try {
            String shaped = ARABIC_SHAPING.shape(line);
            Bidi bidi = new Bidi(shaped, Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);
            String visual = bidi.writeReordered(Bidi.DO_MIRRORING);
            String normalized = Normalizer.normalize(visual, Normalizer.Form.NFKC);
            return new PreparedLine(normalized, true);
        } catch (ArabicShapingException e) {
            throw new RuntimeException("Unable to shape RTL text", e);
        }
    }

    private boolean containsRtlCharacters(String line) {
        return RTL_PATTERN.matcher(line).find();
    }

    private record PreparedLine(String text, boolean rtl) {
    }
}
