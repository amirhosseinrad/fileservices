package ir.ipaam.fileservice.api.controller;

import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.ArabicShapingException;
import com.ibm.icu.text.Bidi;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import io.swagger.v3.oas.annotations.Operation;
import ir.ipaam.fileservice.api.dto.ContractRequest;
import ir.ipaam.fileservice.api.mapper.ContractModelMapper;
import ir.ipaam.fileservice.application.service.HtmlToPdfService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/pdf")
@AllArgsConstructor
public class PdfController {
    private final HtmlToPdfService htmlToPdfService;

    @PostMapping( produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> generate(@Valid @RequestBody Map<String,Object> model) throws Exception {

        ClassPathResource htmlRes = new ClassPathResource("morabehe/index.html");
        ClassPathResource cssRes = new ClassPathResource("morabehe/style.css");
        HtmlToPdfService.ResourceResolver rr =
                HtmlToPdfService.classpathResolver("morabehe");


        byte[] pdf;
        try (InputStream htmlIn = htmlRes.getInputStream();
             InputStream cssIn = cssRes.getInputStream()) {

            pdf = htmlToPdfService.convertXhtmlToPdf(htmlIn, cssIn, model, rr);
        }

        ContentDisposition cd = ContentDisposition.attachment()
                .filename((model.hashCode() +".pdf"), StandardCharsets.UTF_8)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(cd);
        headers.setContentType(MediaType.APPLICATION_PDF);

        return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
    }

    @PostMapping(
            value = "/from-folder",
            produces = MediaType.APPLICATION_PDF_VALUE
    )
    @Operation(summary = "Generate PDF from local folder (HTML + CSS + fonts + images)")
    public ResponseEntity<byte[]> generateFromFolder(
            @RequestParam("folderPath") String folderPath,
            @Valid @RequestBody Map<String, Object> model
    ) throws Exception {

        Path baseDir = Paths.get(folderPath);
        if (!Files.exists(baseDir) || !Files.isDirectory(baseDir)) {
            throw new FileNotFoundException("Folder not found: " + folderPath);
        }

        // 1️⃣ Find first HTML and CSS file
        Path htmlPath = Files.walk(baseDir)
                .filter(p -> p.toString().toLowerCase().endsWith(".html"))
                .findFirst()
                .orElseThrow(() -> new FileNotFoundException("No HTML file found in folder " + folderPath));

        Path cssPath = Files.walk(baseDir)
                .filter(p -> p.toString().toLowerCase().endsWith(".css"))
                .findFirst()
                .orElse(null); // optional

        // 2️⃣ Read the HTML and CSS
        String html = Files.readString(htmlPath, StandardCharsets.UTF_8)
                .replace("&nbsp;", "&#160;")
                .replace("&ensp;", "&#8194;")
                .replace("&emsp;", "&#8195;");

        String css = (cssPath != null)
                ? Files.readString(cssPath, StandardCharsets.UTF_8)
                : "";

        // 3️⃣ Base URI for relative resources (images/fonts)
        String baseUri = htmlPath.getParent().toUri().toString();

        // 4️⃣ Convert HTML → PDF
        byte[] pdf = htmlToPdfService.convertXhtmlToPdf(
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(css.getBytes(StandardCharsets.UTF_8)),
                model,
                HtmlToPdfService.classpathResolver(baseUri)
        );

        // 5️⃣ Build response
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(model.hashCode() + ".pdf", StandardCharsets.UTF_8)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(cd);
        headers.setContentType(MediaType.APPLICATION_PDF);

        return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
    }


    @PostMapping(value = "/by-third-party", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> morabaha(@Valid @RequestBody ContractRequest req) throws Exception {
        Map<String, Object> model = ContractModelMapper.toModel(req);

        // 1) Load HTML as string and fill placeholders (or use Thymeleaf)
        String html = new String(
                new ClassPathResource("morabehe/index.html").getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8
        );
        html = html

                .replace("&nbsp;", "&#160;")     // replace undefined entity
                .replace("&ensp;", "&#8194;")
                .replace("&emsp;", "&#8195;");


        html = HtmlToPdfService.renderTemplate(html, model);
        html = shapeArabicText(html);   // the safe non-escaping version

        // 2) Base URL so relative images like ./images/logo.png resolve
        String baseUrl = Objects.requireNonNull(
                Thread.currentThread().getContextClassLoader().getResource("morabehe/")
        ).toExternalForm();

        // 3) Build PDF
        var out = new java.io.ByteArrayOutputStream();
        var builder = new com.openhtmltopdf.pdfboxout.PdfRendererBuilder();
        builder.useFastMode();
        builder.withHtmlContent(html, baseUrl);
        // Embed Persian fonts (Identity-H)

        builder.useFont(
                () -> {
                    try {
                        return new ClassPathResource("morabehe/fonts/IRANSans.ttf").getInputStream();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                "IRANSans",
                300,
                BaseRendererBuilder.FontStyle.NORMAL,
                true
        );
        builder.useFont(
                () -> {
                    try {
                        return new ClassPathResource("fonts/Vazirmatn-Bold.ttf").getInputStream();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                "Vazirmatn",
                700,
                BaseRendererBuilder.FontStyle.NORMAL,
                true
        );

        File reg = copyToTemp("morabehe/fonts/Vazirmatn-Regular.ttf");
        File bold = copyToTemp("morabehe/fonts/Vazirmatn-Bold.ttf");


        builder.useFont(reg, "Vazirmatn", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
        builder.useFont(bold, "Vazirmatn", 700, BaseRendererBuilder.FontStyle.NORMAL, true);

        builder.toStream(out);
        builder.run();

        var cd = ContentDisposition.attachment()
                .filename(("Morabaha-" + req.getPdf_code() + ".pdf"), java.nio.charset.StandardCharsets.UTF_8)
                .build();
        var headers = new HttpHeaders();
        headers.setContentDisposition(cd);
        headers.setContentType(MediaType.APPLICATION_PDF);
        return new ResponseEntity<>(out.toByteArray(), headers, HttpStatus.OK);
    }


    // helper
    private static File copyToTemp(String classpath) throws IOException {
        try (InputStream in = new ClassPathResource(classpath).getInputStream()) {
            File f = File.createTempFile("font-", ".ttf");
            f.deleteOnExit();
            try (OutputStream out = new FileOutputStream(f)) {
                in.transferTo(out);
            }
            return f;
        }
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
                // flush any text before the tag
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

        // flush trailing text
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
            reordered.replace("گ", "\uE001")
                    .replace("چ", "\uE002")
                    .replace("پ", "\uE003")
                    .replace("ژ", "\uE004");

            return reordered;
        } catch (ArabicShapingException e) {
            return text; // fallback: return unshaped text
        }
    }


}
