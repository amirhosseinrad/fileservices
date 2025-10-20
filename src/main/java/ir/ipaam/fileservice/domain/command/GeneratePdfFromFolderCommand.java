package ir.ipaam.fileservice.domain.command;

import java.util.Map;

public record GeneratePdfFromFolderCommand(String folderPath, Map<String, Object> model) {
}
