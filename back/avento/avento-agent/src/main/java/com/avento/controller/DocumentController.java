package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.*;
import com.avento.api.dto.BaseResponse;
import com.avento.service.DocumentReaderService;
import com.avento.service.dto.DocumentReadResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Logger logger = LoggerFactory.getLogger(DocumentController.class);
    private static final Set<String> SUPPORTED_EXTENSIONS = loadSupportedExtensions();

    private static Set<String> loadSupportedExtensions() {
        try (var in = new org.springframework.core.io.ClassPathResource("agent/policies/supported_extensions.txt")
                .getInputStream()) {
            return java.util.Arrays.stream(
                            new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\r?\\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            logger.warn(
                    "Não foi possível carregar a lista de extensões suportadas. O upload de documentos poderá falhar.",
                    e);
            return java.util.Set.of();
        }
    }

    private final DocumentReaderService documentReaderService;
    private final Path temporaryDirectory;
    private final int maxContextChars;

    public DocumentController(
            DocumentReaderService documentReaderService,
            @Value("${avento.documents.upload-temp-directory:${java.io.tmpdir}/avento-document-uploads}")
                    String temporaryDirectory,
            @Value("${avento.documents.attachment-max-context-chars:5000}") int maxContextChars) {
        this.documentReaderService = documentReaderService;
        this.temporaryDirectory = Path.of(temporaryDirectory).toAbsolutePath().normalize();
        this.maxContextChars = Math.max(500, maxContextChars);
    }

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BaseResponse<DocumentUploadResult>> extract(@RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione um documento não vazio.");
        }

        String originalName = safeOriginalName(file.getOriginalFilename());
        String extension = extensionOf(originalName);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Formato não suportado. Envie PDF, Office, EPUB, ZIP, texto ou código-fonte.");
        }

        Path temporaryFile = null;
        try {
            Files.createDirectories(temporaryDirectory);
            temporaryFile = Files.createTempFile(temporaryDirectory, "upload-", "." + extension);
            file.transferTo(temporaryFile);
            DocumentReadResult result = documentReaderService.readTemporary(temporaryFile);
            String content = truncate(result.content(), maxContextChars);
            boolean truncated = result.truncated() || result.content().length() > maxContextChars;
            return ApiResponses.ok(new DocumentUploadResult(
                    originalName,
                    file.getContentType() == null ? result.mediaType() : file.getContentType(),
                    result.bytes(),
                    result.reader(),
                    content,
                    truncated));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Não foi possível ler o documento: " + exception.getMessage(),
                    exception);
        } finally {
            deleteTemporaryFile(temporaryFile);
        }
    }

    private String safeOriginalName(String originalName) {
        String fallback = originalName == null || originalName.isBlank() ? "documento" : originalName;
        String normalized = fallback.replace('\\', '/').replace('\n', ' ').replace('\r', ' ');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 && dot < filename.length() - 1
                ? filename.substring(dot + 1).toLowerCase(Locale.ROOT)
                : "";
    }

    private String truncate(String content, int limit) {
        if (content == null || content.length() <= limit) {
            return content == null ? "" : content;
        }
        return content.substring(0, limit);
    }

    private void deleteTemporaryFile(Path temporaryFile) {
        if (temporaryFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryFile);
        } catch (IOException exception) {
            logger.warn("Could not delete temporary document {}", temporaryFile, exception);
        }
    }
}
