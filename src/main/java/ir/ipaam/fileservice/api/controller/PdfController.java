package ir.ipaam.fileservice.api.controller;

import ir.ipaam.fileservice.application.service.HtmlToPdfService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/pdf")
public class PdfController {
    private final HtmlToPdfService htmlToPdfService = new HtmlToPdfService();

    @PostMapping(
            value = "/convert-files",
            consumes = "multipart/form-data",
            produces = "application/pdf"
    )
    public ResponseEntity<byte[]> convertFiles(
            @RequestParam("html") MultipartFile htmlFile,
            @RequestParam("css") MultipartFile cssFile
    ) throws IOException {

        String htmlContent = new String(htmlFile.getBytes(), StandardCharsets.UTF_8);
        String cssContent = new String(cssFile.getBytes(), StandardCharsets.UTF_8);

        // merge <style> into <head> (if missing, create it)
        String mergedHtml = HtmlToPdfService.mergeHtmlAndCss(htmlContent, cssContent);

        String xhtml = toXhtml(mergedHtml);
        byte[] pdf = htmlToPdfService.convertXhtmlToPdf(xhtml);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"document.pdf\"")
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .body(pdf);
    }

    public static String toXhtml(String html) {
        if (html == null) return "";
        return Jsoup.parse(html)
                .outputSettings(new Document.OutputSettings()
                        .syntax(Document.OutputSettings.Syntax.xml)
                        .escapeMode(Entities.EscapeMode.xhtml)
                        .prettyPrint(true))
                .outerHtml();
    }

}
