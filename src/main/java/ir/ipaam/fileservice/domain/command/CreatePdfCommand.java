package ir.ipaam.fileservice.domain.command;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
public class CreatePdfCommand{
    private String conversionId;
    private String text;
    private String fontName;
}


