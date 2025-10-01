package ir.ipaam.fileservice.domain;

import ir.ipaam.fileservice.application.service.PdfGenerator;
import ir.ipaam.fileservice.domain.event.PdfCreatedEvent;
import org.axonframework.eventhandling.EventHandler;
import org.springframework.stereotype.Component;

@Component
public class PdfProjection {

    private final PdfGenerator pdfGenerator; // infrastructure service

    public PdfProjection(PdfGenerator pdfGenerator) {
        this.pdfGenerator = pdfGenerator;
    }

    @EventHandler
    public void on(PdfCreatedEvent event) {
        if (event.getPdfBytes() == null) {
            byte[] pdf = pdfGenerator.generate(event);
            event.setPdfBytes(pdf);
        }
        // store in DB, MinIO, filesystem, etc.
    }
}
