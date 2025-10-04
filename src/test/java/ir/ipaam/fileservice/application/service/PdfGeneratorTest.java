package ir.ipaam.fileservice.application.service;

import ir.ipaam.fileservice.domain.event.PdfCreatedEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfGeneratorTest {

    @Test
    void generateShouldHandleMultilineText() {
        PdfGenerator generator = new PdfGenerator(new HtmlCssPdfGenerator());
        PdfCreatedEvent event = new PdfCreatedEvent();
        event.setText("First line\nSecond line\nThird line");

        byte[] pdfBytes = generator.generate(event);

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 0, "Generated PDF should not be empty");
    }

    @Test
    void generateShouldSupportPersianCharacters() {
        PdfGenerator generator = new PdfGenerator(new HtmlCssPdfGenerator());
        PdfCreatedEvent event = new PdfCreatedEvent();
        event.setText("این یک متن نمونه است\nمتن دوم برای آزمایش");

        byte[] pdfBytes = generator.generate(event);

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 0, "Generated PDF with Persian text should not be empty");
    }

    @Test
    void generateShouldSupportPersianSpecificLetters() {
        PdfGenerator generator = new PdfGenerator();
        PdfCreatedEvent event = new PdfCreatedEvent();
        event.setText("ی گ پ چ");

        byte[] pdfBytes = generator.generate(event);

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 0, "Generated PDF with Persian specific letters should not be empty");
    }
}
