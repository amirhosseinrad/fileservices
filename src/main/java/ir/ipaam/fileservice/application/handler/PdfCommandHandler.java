package ir.ipaam.fileservice.application.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import ir.ipaam.fileservice.api.dto.ContractRequest;
import ir.ipaam.fileservice.api.mapper.ContractModelMapper;
import ir.ipaam.fileservice.application.service.HtmlToPdfService;
import ir.ipaam.fileservice.application.service.htmltopdf.ResourceResolver;
import ir.ipaam.fileservice.application.util.ArabicTextUtils;
import ir.ipaam.fileservice.domain.command.GeneratePdfFromContentCommand;
import ir.ipaam.fileservice.domain.command.GeneratePdfFromFolderCommand;
import ir.ipaam.fileservice.domain.command.GeneratePdfFromTemplateCommand;
import ir.ipaam.fileservice.domain.command.GeneratePdfFromThirdPartyCommand;
import ir.ipaam.fileservice.domain.command.GeneratePdfFromZipCommand;
import ir.ipaam.fileservice.domain.dto.PdfGenerationResult;
import lombok.RequiredArgsConstructor;
import org.axonframework.commandhandling.CommandHandler;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
@RequiredArgsConstructor
public class PdfCommandHandler {

    private final HtmlToPdfService htmlToPdfService;
    private final ObjectMapper objectMapper;

    @CommandHandler
    public PdfGenerationResult handle(GeneratePdfFromTemplateCommand command) throws Exception {
        ClassPathResource htmlRes = new ClassPathResource("morabehe/index.html");
        ClassPathResource cssRes = new ClassPathResource("morabehe/style.css");
        ResourceResolver resolver = HtmlToPdfService.classpathResolver("morabehe");

        byte[] pdf;
        try (InputStream htmlIn = htmlRes.getInputStream();
             InputStream cssIn = cssRes.getInputStream()) {
            pdf = htmlToPdfService.convertXhtmlToPdf(htmlIn, cssIn, command.model(), resolver);
        }

        String fileName = resolveFileName(command.model(), UUID.randomUUID().toString());
        return new PdfGenerationResult(fileName, pdf);
    }

    @CommandHandler
    public PdfGenerationResult handle(GeneratePdfFromFolderCommand command) throws Exception {
        Path baseDir = Paths.get(command.folderPath());
        if (!Files.exists(baseDir) || !Files.isDirectory(baseDir)) {
            throw new FileNotFoundException("Folder not found: " + command.folderPath());
        }

        Path htmlPath = Files.walk(baseDir)
                .filter(p -> p.toString().toLowerCase().endsWith(".html"))
                .findFirst()
                .orElseThrow(() -> new FileNotFoundException("No HTML file found in folder " + command.folderPath()));

        Path cssPath = Files.walk(baseDir)
                .filter(p -> p.toString().toLowerCase().endsWith(".css"))
                .findFirst()
                .orElse(null);

        String html = Files.readString(htmlPath, StandardCharsets.UTF_8)
                .replace("&nbsp;", "&#160;")
                .replace("&ensp;", "&#8194;")
                .replace("&emsp;", "&#8195;");

        String css = (cssPath != null)
                ? Files.readString(cssPath, StandardCharsets.UTF_8)
                : "";

        String baseUri = htmlPath.getParent().toUri().toString();

        byte[] pdf = htmlToPdfService.convertXhtmlToPdf(
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(css.getBytes(StandardCharsets.UTF_8)),
                command.model(),
                HtmlToPdfService.fileResolver(baseUri)
        );

        String fileName = resolveFileName(command.model(), UUID.randomUUID().toString());
        return new PdfGenerationResult(fileName, pdf);
    }

    @CommandHandler
    public PdfGenerationResult handle(GeneratePdfFromThirdPartyCommand command) throws Exception {
        ContractRequest request = command.request();
        Map<String, Object> model = ContractModelMapper.toModel(request);

        String html = new String(
                new ClassPathResource("morabehe/index.html").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        html = html
                .replace("&nbsp;", "&#160;")
                .replace("&ensp;", "&#8194;")
                .replace("&emsp;", "&#8195;");

        html = HtmlToPdfService.renderTemplate(html, model);
        html = ArabicTextUtils.shapeArabicText(html);

        String baseUrl = Objects.requireNonNull(
                Thread.currentThread().getContextClassLoader().getResource("morabehe/")
        ).toExternalForm();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.openhtmltopdf.pdfboxout.PdfRendererBuilder builder = new com.openhtmltopdf.pdfboxout.PdfRendererBuilder();
        builder.useFastMode();
        builder.withHtmlContent(html, baseUrl);

        builder.useFont(
                () -> new ClassPathResource("morabehe/fonts/IRANSans.ttf").getInputStream(),
                "IRANSans",
                300,
                BaseRendererBuilder.FontStyle.NORMAL,
                true
        );
        builder.useFont(
                () -> new ClassPathResource("fonts/Vazirmatn-Bold.ttf").getInputStream(),
                "Vazirmatn",
                700,
                BaseRendererBuilder.FontStyle.NORMAL,
                true
        );

        File reg = copyToTemp("morabehe/fonts/Vazirmatn-Regular.ttf");
        File bold = copyToTemp("morabehe/fonts/Vazirmatn-Bold.ttf");

        builder.useFont(reg, "Vazirmatn", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
        builder.useFont(bold, "Vazirmatn", 700, BaseRendererBuilder.FontStyle.NORMAL, true);

        builder.toStream(out);
        builder.run();

        String fileName = "Morabaha-" + (request.getPdf_code() != null ? request.getPdf_code() : "generated") + ".pdf";
        return new PdfGenerationResult(fileName, out.toByteArray());
    }

    @CommandHandler
    public PdfGenerationResult handle(GeneratePdfFromContentCommand command) throws Exception {
        byte[] pdf = htmlToPdfService.convertXhtmlToPdf(
                new ByteArrayInputStream(command.html().getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(command.css().getBytes(StandardCharsets.UTF_8)),
                command.model(),
                HtmlToPdfService.fileResolver("")
        );

        String fileName = command.hashCode() + ".pdf";
        return new PdfGenerationResult(fileName, pdf);
    }

    @CommandHandler
    public PdfGenerationResult handle(GeneratePdfFromZipCommand command) throws Exception {
        Path tempDir = Files.createTempDirectory("pdfzip_");
        try {
            unzip(new ByteArrayInputStream(command.zipContent()), tempDir);

            Optional<Path> htmlPath = findFile(tempDir, ".html");
            Path html = htmlPath.orElseThrow(() -> new FileNotFoundException("No HTML file found in ZIP"));
            Optional<Path> cssPath = findFile(tempDir, ".css");

            Map<String, Object> model = objectMapper.readValue(command.modelJson(), Map.class);

            String htmlContent = Files.readString(html, StandardCharsets.UTF_8)
                    .replace("&nbsp;", "&#160;")
                    .replace("&ensp;", "&#8194;")
                    .replace("&emsp;", "&#8195;");
            String cssContent = cssPath.map(path -> {
                try {
                    return Files.readString(path, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to read CSS file", e);
                }
            }).orElse("");

            String baseUri = html.getParent().toUri().toString();

            byte[] pdf = htmlToPdfService.convertXhtmlToPdf(
                    new ByteArrayInputStream(htmlContent.getBytes(StandardCharsets.UTF_8)),
                    new ByteArrayInputStream(cssContent.getBytes(StandardCharsets.UTF_8)),
                    model,
                    HtmlToPdfService.fileResolver(baseUri)
            );

            String fileName = resolveFileName(model, UUID.randomUUID().toString());
            return new PdfGenerationResult(fileName, pdf);
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private static File copyToTemp(String classpath) throws IOException {
        try (InputStream in = new ClassPathResource(classpath).getInputStream()) {
            File file = File.createTempFile("font-", ".ttf");
            file.deleteOnExit();
            try (OutputStream out = new FileOutputStream(file)) {
                in.transferTo(out);
            }
            return file;
        }
    }

    private static String resolveFileName(Map<String, Object> model, String fallback) {
        if (model == null || model.isEmpty()) {
            return fallback + ".pdf";
        }
        return model.hashCode() + ".pdf";
    }

    private void unzip(InputStream inputStream, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path newFile = targetDir.resolve(entry.getName()).normalize();
                if (!newFile.startsWith(targetDir)) {
                    throw new IOException("ZIP entry outside target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(newFile);
                } else {
                    Files.createDirectories(newFile.getParent());
                    Files.copy(zis, newFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private Optional<Path> findFile(Path dir, String extension) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream.filter(p -> p.toString().toLowerCase().endsWith(extension))
                    .findFirst();
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (dir == null) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
