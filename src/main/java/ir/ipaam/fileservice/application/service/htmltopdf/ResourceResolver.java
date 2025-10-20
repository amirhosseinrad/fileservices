package ir.ipaam.fileservice.application.service.htmltopdf;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface ResourceResolver {
    InputStream open(String src) throws IOException;
}
