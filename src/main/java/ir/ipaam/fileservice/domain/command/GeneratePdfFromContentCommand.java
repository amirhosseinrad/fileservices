package ir.ipaam.fileservice.domain.command;

import java.util.Map;

public record GeneratePdfFromContentCommand(String html, String css, Map<String, Object> model) {
}
