package ir.ipaam.fileservice.api.controller;


import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;
import ir.ipaam.fileservice.application.service.HtmlCssPdfGenerator;
import ir.ipaam.fileservice.application.service.PdfGenerator;
import ir.ipaam.fileservice.domain.command.CreatePdfCommand;
import org.axonframework.commandhandling.gateway.CommandGateway;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/conversions")
public class ConversionController {

    private final CommandGateway commandGateway;
    private final PdfGenerator pdfGenerator;
    private final HtmlCssPdfGenerator htmlCssPdfGenerator;

    public ConversionController(CommandGateway commandGateway, PdfGenerator pdfGenerator, HtmlCssPdfGenerator htmlCssPdfGenerator) {
        this.commandGateway = commandGateway;
        this.pdfGenerator = pdfGenerator;
        this.htmlCssPdfGenerator = htmlCssPdfGenerator;
    }

    @PostMapping(value = "/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> convertTxtToPdf(@RequestPart("file") MultipartFile file) throws Exception {
        String text = readTextContent(file);
        String id = UUID.randomUUID().toString();

        byte[] pdfBytes = pdfGenerator.generate(text);

        // Send command
        commandGateway.sendAndWait(new CreatePdfCommand(id, text, "IranSans", pdfBytes));
        return buildPdfResponse(pdfBytes, "converted.pdf");
    }

    @PostMapping(value = "/pdf/html", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> convertHtmlToPdf(@RequestPart("html") MultipartFile htmlFile,
                                                   @RequestPart(value = "css", required = false) MultipartFile cssFile) throws Exception {
        String htmlContent = readTextContent(htmlFile);
        String cssContent = cssFile != null ? readTextContent(cssFile) : "";
        String id = UUID.randomUUID().toString();

        byte[] pdfBytes = htmlCssPdfGenerator.generate(htmlContent, cssContent);

        commandGateway.sendAndWait(new CreatePdfCommand(id, htmlContent, "IranSans", pdfBytes));
        return buildPdfResponse(pdfBytes, "converted-html.pdf");
    }

    private ResponseEntity<byte[]> buildPdfResponse(byte[] pdf, String filename) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(disposition);
        headers.setContentType(MediaType.APPLICATION_PDF);

        return ResponseEntity.ok()
                .headers(headers)
                .body(pdf);
    }

    private String readTextContent(MultipartFile file) throws Exception {
        byte[] bytes = file.getBytes();
        if (bytes.length == 0) {
            return "";
        }

        CharsetDetector detector = new CharsetDetector();
        detector.setText(bytes);
        CharsetMatch match = detector.detect();

        Charset charset = StandardCharsets.UTF_8;
        if (match != null && match.getName() != null) {
            try {
                charset = Charset.forName(match.getName());
            } catch (Exception ignored) {
                charset = StandardCharsets.UTF_8;
            }
        }

        String text = new String(bytes, charset);

        text = stripBom(text);
        return text;
    }

    private String stripBom(String input) {
        if (input == null || input.isEmpty()) {
            return input == null ? "" : input;
        }
        if (input.charAt(0) == '\uFEFF') {
            return input.substring(1);
        }
        return input;
    }
}
