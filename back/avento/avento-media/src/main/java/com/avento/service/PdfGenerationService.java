package com.avento.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PdfGenerationService {

    private final GeneratedMediaAssetService generatedMediaAssetService;
    private final ObjectMapper mapper;

    @Value("${avento.media.directory:}")
    private String mediaDirectoryPath;

    public ObjectNode generate(String title, String markdown, String html, Long chatId, UUID userId)
            throws IOException {
        String contentHtml = html;
        if (markdown != null && !markdown.isBlank()) {
            List<Extension> extensions = List.of(TablesExtension.create());
            Parser parser = Parser.builder().extensions(extensions).build();
            HtmlRenderer renderer =
                    HtmlRenderer.builder().extensions(extensions).build();
            contentHtml = renderer.render(parser.parse(markdown));
        }

        if (contentHtml == null) {
            contentHtml = "";
        }

        String xhtml = """
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                <title>%s</title>
                <style>
                  body { font-family: sans-serif; padding: 20px; line-height: 1.5; }
                  table { border-collapse: collapse; width: 100%%; margin-bottom: 20px; }
                  th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
                  th { background-color: #f2f2f2; }
                  h1 { color: #333; }
                  pre { background-color: #f8f8f8; padding: 10px; border-radius: 4px; overflow-x: auto; }
                  code { font-family: monospace; }
                </style>
                </head>
                <body>
                <h1>%s</h1>
                %s
                </body>
                </html>
                """.formatted(escapeHtml(title), escapeHtml(title), contentHtml);

        Path mediaDirectory = Paths.get(
                mediaDirectoryPath.isBlank()
                        ? System.getProperty("user.home") + "/Pictures/Avento Generated Images"
                        : mediaDirectoryPath);
        if (!Files.exists(mediaDirectory)) {
            Files.createDirectories(mediaDirectory);
        }

        String filename = "avento-doc-" + Instant.now().toEpochMilli() + ".pdf";
        Path out = mediaDirectory.resolve(filename);

        try (OutputStream os = Files.newOutputStream(out)) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(xhtml, null);
            builder.toStream(os);
            builder.run();
        } catch (Exception e) {
            log.error("Failed to generate PDF", e);
            throw new IOException("Failed to generate PDF", e);
        }

        generatedMediaAssetService.register(out, chatId, userId, "document");

        ObjectNode result = mapper.createObjectNode();
        result.put("status", "success");
        result.put("filename", filename);
        result.put("path", out.toString());
        result.put("mediaType", "document");

        return result;
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
