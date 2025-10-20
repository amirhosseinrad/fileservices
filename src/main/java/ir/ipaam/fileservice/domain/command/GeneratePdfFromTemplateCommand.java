package ir.ipaam.fileservice.domain.command;

import java.util.Map;

public record GeneratePdfFromTemplateCommand(Map<String, Object> model) {
}
