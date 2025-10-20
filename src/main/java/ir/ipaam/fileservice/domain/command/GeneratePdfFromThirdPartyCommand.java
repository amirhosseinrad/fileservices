package ir.ipaam.fileservice.domain.command;

import ir.ipaam.fileservice.api.dto.ContractRequest;

public record GeneratePdfFromThirdPartyCommand(ContractRequest request) {
}
