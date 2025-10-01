package ir.ipaam.fileservice.api.controller;


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

    public ConversionController(CommandGateway commandGateway, PdfGenerator pdfGenerator) {
        this.commandGateway = commandGateway;
        this.pdfGenerator = pdfGenerator;
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
}
