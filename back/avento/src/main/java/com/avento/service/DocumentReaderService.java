package com.avento.service;

import com.avento.service.dto.*;
import com.avento.service.support.ProjectPaths;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DocumentReaderService {

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt",
            "md",
            "markdown",
            "csv",
            "json",
            "jsonl",
            "xml",
            "yaml",
            "yml",
            "toml",
            "ini",
            "log",
            "java",
            "kt",
            "kts",
            "js",
            "jsx",
            "ts",
            "tsx",
            "css",
            "scss",
            "html",
            "htm",
            "sql",
            "sh",
            "zsh",
            "bash",
            "py",
            "rb",
            "go",
            "rs",
            "c",
            "h",
            "cpp",
            "hpp",
            "gradle",
            "properties");

    private final WorkspaceAccessService workspaceAccessService;
    private final Path markitdownCommand;
    private final Duration timeout;
    private final int maxOutputChars;
    private final long maxFileBytes;

    public DocumentReaderService(
            WorkspaceAccessService workspaceAccessService,
            @Value("${avento.documents.markitdown-command:.avento-tools/mcp/bin/markitdown}") String markitdownCommand,
            @Value("${avento.documents.timeout:120s}") Duration timeout,
            @Value("${avento.documents.max-output-chars:200000}") int maxOutputChars,
            @Value("${avento.documents.max-file-size:100MB}") org.springframework.util.unit.DataSize maxFileSize) {
        this.workspaceAccessService = workspaceAccessService;
        this.markitdownCommand = ProjectPaths.resolve(markitdownCommand);
        this.timeout = timeout;
        this.maxOutputChars = Math.max(1, maxOutputChars);
        this.maxFileBytes = Math.max(1, maxFileSize.toBytes());
    }

    public DocumentReadResult read(String requestedPath) throws IOException {
        Path file = workspaceAccessService.requireAuthorized(requestedPath);
        return readFile(file);
    }

    public DocumentReadResult readTemporary(Path temporaryFile) throws IOException {
        return readFile(temporaryFile.toAbsolutePath().normalize());
    }

    private DocumentReadResult readFile(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Path is not a file: " + file);
        }

        long size = Files.size(file);
        if (size > maxFileBytes) {
            throw new IllegalArgumentException(
                    "Document exceeds the configured size limit of " + maxFileBytes + " bytes");
        }

        String mediaType = Files.probeContentType(file);
        if (isPlainText(file, mediaType)) {
            return limitedResult(file, mediaType, "plain-text", Files.readString(file, StandardCharsets.UTF_8));
        }
        if (!Files.isExecutable(markitdownCommand)) {
            throw new IllegalStateException(
                    "MarkItDown is not installed. Run scripts/setup-local-mcps.sh before reading this format.");
        }

        Process process = new ProcessBuilder(markitdownCommand.toString(), file.toString())
                .redirectErrorStream(true)
                .start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readOutput(process.getInputStream()));
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Document reading was interrupted", exception);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Document reading timed out after " + timeout);
        }

        String content = output.join();
        if (process.exitValue() != 0) {
            throw new IOException("MarkItDown failed: " + truncate(content, 4000));
        }
        return limitedResult(file, mediaType, "markitdown", content);
    }

    public boolean isMarkitdownAvailable() {
        return Files.isExecutable(markitdownCommand);
    }

    private DocumentReadResult limitedResult(Path file, String mediaType, String reader, String content)
            throws IOException {
        boolean truncated = content.length() > maxOutputChars;
        return new DocumentReadResult(
                file.toString(),
                mediaType == null ? "application/octet-stream" : mediaType,
                Files.size(file),
                reader,
                truncate(content, maxOutputChars),
                truncated);
    }

    private boolean isPlainText(Path file, String mediaType) {
        if (mediaType != null
                && (mediaType.startsWith("text/") || mediaType.contains("json") || mediaType.contains("xml"))) {
            return true;
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private String readOutput(InputStream input) {
        try (input;
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read MarkItDown output", exception);
        }
    }

    private String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }
}
