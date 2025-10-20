package ir.ipaam.fileservice.domain.dto;

import java.util.Objects;

public record PdfGenerationResult(String fileName, byte[] pdfBytes) {

    public PdfGenerationResult {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(pdfBytes, "pdfBytes");
    }
}
