package ir.ipaam.fileservice.domain.event;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PdfCreatedEvent {
    private String conversionId;
    private String text;
    private byte[] pdfBytes;
}
