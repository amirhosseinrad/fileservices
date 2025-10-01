package ir.ipaam.fileservice.api.controller;


import ir.ipaam.fileservice.domain.command.CreatePdfCommand;
import ir.ipaam.fileservice.domain.event.PdfCreatedEvent;
import org.axonframework.commandhandling.gateway.CommandGateway;
import org.axonframework.queryhandling.QueryGateway;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/conversions")
public class ConversionController {

    private final CommandGateway commandGateway;

    public ConversionController(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    @PostMapping(value = "/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> convertTxtToPdf(@RequestPart("file") MultipartFile file) throws Exception {
        String text = new String(file.getBytes()); // read Persian text
        String id = UUID.randomUUID().toString();

        // Send command
        PdfCreatedEvent event = commandGateway.sendAndWait(new CreatePdfCommand(id,text,"IranSans"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=converted.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(event.getPdfBytes());
    }
}
