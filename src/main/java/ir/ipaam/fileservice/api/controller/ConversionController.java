package ir.ipaam.fileservice.api.controller;


import ir.ipaam.fileservice.application.service.HtmlCssPdfGenerator;
import ir.ipaam.fileservice.application.service.PdfGenerator;
import ir.ipaam.fileservice.domain.command.CreatePdfCommand;
import org.axonframework.commandhandling.gateway.CommandGateway;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
        String text = new String(file.getBytes(), StandardCharsets.UTF_8); // read Persian text
        String id = UUID.randomUUID().toString();

        byte[] pdfBytes = pdfGenerator.generate(text);

        // Send command
        commandGateway.sendAndWait(new CreatePdfCommand(id, text, "IranSans", pdfBytes));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=converted.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    @PostMapping(value = "/pdf/html", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> convertHtmlToPdf(@RequestPart("html") MultipartFile htmlFile,
                                                   @RequestPart(value = "css", required = false) MultipartFile cssFile) throws Exception {
        String htmlContent = new String(htmlFile.getBytes(), StandardCharsets.UTF_8);
        String cssContent = cssFile != null ? new String(cssFile.getBytes(), StandardCharsets.UTF_8) : "";
        String id = UUID.randomUUID().toString();

        byte[] pdfBytes = htmlCssPdfGenerator.generate(htmlContent, cssContent);

        commandGateway.sendAndWait(new CreatePdfCommand(id, htmlContent, "IranSans", pdfBytes));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=converted-html.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }
}
