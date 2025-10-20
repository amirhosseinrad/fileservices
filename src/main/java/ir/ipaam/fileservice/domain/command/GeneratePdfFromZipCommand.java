package ir.ipaam.fileservice.domain.command;

public record GeneratePdfFromZipCommand(byte[] zipContent, String modelJson) {
}
