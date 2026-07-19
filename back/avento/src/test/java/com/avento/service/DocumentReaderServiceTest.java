package com.avento.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.service.dto.DocumentReadResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class DocumentReaderServiceTest {

    private final String originalProjectRoot = System.getProperty("avento.project.root");

    @TempDir
    Path tempDir;

    @AfterEach
    void restoreProjectRoot() {
        if (originalProjectRoot == null) {
            System.clearProperty("avento.project.root");
        } else {
            System.setProperty("avento.project.root", originalProjectRoot);
        }
    }

    @Test
    void readsTextDirectlyInsideAuthorizedWorkspace() throws Exception {
        WorkspaceAccessService access = new WorkspaceAccessService();
        access.registerWorkspaceRoot(tempDir.toString());
        Path file = Files.writeString(tempDir.resolve("notes.md"), "# Avento\nconteudo");
        DocumentReaderService service = service(access, tempDir.resolve("missing-markitdown"), 1000);

        DocumentReadResult result = service.read(file.toString());

        assertEquals("plain-text", result.reader());
        assertEquals("# Avento\nconteudo", result.content());
        assertFalse(result.truncated());
    }

    @Test
    void convertsBinaryDocumentWithConfiguredMarkitdownCommand() throws Exception {
        WorkspaceAccessService access = new WorkspaceAccessService();
        access.registerWorkspaceRoot(tempDir.toString());
        Path file = Files.write(tempDir.resolve("sample.pdf"), new byte[] {1, 2, 3});
        Path command = tempDir.resolve("markitdown-test");
        Files.writeString(command, "#!/bin/sh\nprintf '# Converted\\nfrom pdf'\n");
        command.toFile().setExecutable(true);

        DocumentReadResult result = service(access, command, 1000).read(file.toString());

        assertEquals("markitdown", result.reader());
        assertTrue(result.content().contains("Converted"));
    }

    @Test
    void resolvesRelativeMarkitdownCommandFromProjectRoot() throws Exception {
        System.setProperty("avento.project.root", tempDir.toString());
        Path command = tempDir.resolve(".avento-tools/mcp/bin/markitdown");
        Files.createDirectories(command.getParent());
        Files.writeString(command, "#!/bin/sh\nprintf '# Converted from project root'");
        command.toFile().setExecutable(true);
        Path file = Files.write(tempDir.resolve("sample.pdf"), new byte[] {1, 2, 3});

        DocumentReadResult result = service(
                        new WorkspaceAccessService(), Path.of(".avento-tools/mcp/bin/markitdown"), 1000)
                .readTemporary(file);

        assertEquals("markitdown", result.reader());
        assertTrue(result.content().contains("project root"));
    }

    @Test
    void rejectsFilesOutsideAuthorizedWorkspace() throws Exception {
        WorkspaceAccessService access = new WorkspaceAccessService();
        Path allowed = Files.createDirectory(tempDir.resolve("allowed"));
        access.registerWorkspaceRoot(allowed.toString());
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "secret");

        assertThrows(
                SecurityException.class,
                () -> service(access, tempDir.resolve("missing"), 1000).read(outside.toString()));
    }

    @Test
    void truncatesLargeTextOutput() throws Exception {
        WorkspaceAccessService access = new WorkspaceAccessService();
        access.registerWorkspaceRoot(tempDir.toString());
        Path file = Files.writeString(tempDir.resolve("large.txt"), "1234567890");

        DocumentReadResult result =
                service(access, tempDir.resolve("missing"), 5).read(file.toString());

        assertEquals("12345", result.content());
        assertTrue(result.truncated());
    }

    @Test
    void readsTemporaryUploadWithoutAuthorizingItsDirectory() throws Exception {
        WorkspaceAccessService access = new WorkspaceAccessService();
        Path file = Files.writeString(tempDir.resolve("upload.txt"), "conteúdo enviado");

        DocumentReadResult result =
                service(access, tempDir.resolve("missing"), 1000).readTemporary(file);

        assertEquals("conteúdo enviado", result.content());
        assertEquals("plain-text", result.reader());
    }

    private DocumentReaderService service(WorkspaceAccessService access, Path command, int maxChars) {
        return new DocumentReaderService(
                access, command.toString(), Duration.ofSeconds(5), maxChars, DataSize.ofMegabytes(2));
    }
}
