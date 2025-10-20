package ir.ipaam.fileservice.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.axonframework.commandhandling.gateway.CommandGateway;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ir.ipaam.fileservice.api.dto.ContractRequest;
import ir.ipaam.fileservice.domain.command.GeneratePdfFromContentCommand;
import ir.ipaam.fileservice.domain.command.GeneratePdfFromFolderCommand;
import ir.ipaam.fileservice.domain.command.GeneratePdfFromTemplateCommand;
import ir.ipaam.fileservice.domain.command.GeneratePdfFromThirdPartyCommand;
import ir.ipaam.fileservice.domain.command.GeneratePdfFromZipCommand;
import ir.ipaam.fileservice.domain.dto.PdfGenerationResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/pdf")
@RequiredArgsConstructor
public class HtmlToPdfController {

    private final CommandGateway commandGateway;

    @PostMapping(produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> generate(@Valid @RequestBody Map<String, Object> model) throws Exception {
        PdfGenerationResult result = commandGateway.sendAndWait(new GeneratePdfFromTemplateCommand(model));
        return buildPdfResponse(result);
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
        PdfGenerationResult result = commandGateway.sendAndWait(
                new GeneratePdfFromFolderCommand(folderPath, model)
        );
        return buildPdfResponse(result);
    }

    @PostMapping(value = "/by-third-party", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> morabaha(@Valid @RequestBody ContractRequest req) throws Exception {
        PdfGenerationResult result = commandGateway.sendAndWait(
                new GeneratePdfFromThirdPartyCommand(req)
        );
        return buildPdfResponse(result);
    }

    public record PdfRequest(String html, String css, Map<String, Object> model) {
    }

    @PostMapping(value = "/from-content", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> generateFromContent(
            @RequestBody PdfRequest request
    ) throws Exception {
        PdfGenerationResult result = commandGateway.sendAndWait(
                new GeneratePdfFromContentCommand(request.html(), request.css(), request.model())
        );
        return buildPdfResponse(result);
    }

    @PostMapping(
            value = "/from-zip",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_PDF_VALUE
    )
    @Operation(summary = "Generate PDF from ZIP containing HTML, CSS, images, fonts")
    public ResponseEntity<ByteArrayResource> generateFromZip(
            @RequestPart("file") MultipartFile zipFile,
            @RequestPart("model") String modelJson
    ) throws Exception {
        PdfGenerationResult result = commandGateway.sendAndWait(
                new GeneratePdfFromZipCommand(zipFile.getBytes(), modelJson)
        );
        return buildByteArrayResourceResponse(result);
    }

    private ResponseEntity<byte[]> buildPdfResponse(PdfGenerationResult result) {
        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(result.fileName(), StandardCharsets.UTF_8)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(contentDisposition);
        headers.setContentType(MediaType.APPLICATION_PDF);
        return new ResponseEntity<>(result.pdfBytes(), headers, HttpStatus.OK);
    }

    private ResponseEntity<ByteArrayResource> buildByteArrayResourceResponse(PdfGenerationResult result) {
        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(result.fileName(), StandardCharsets.UTF_8)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(contentDisposition);
        headers.setContentType(MediaType.APPLICATION_PDF);
        return new ResponseEntity<>(new ByteArrayResource(result.pdfBytes()), headers, HttpStatus.OK);
    }
}
