package com.avento.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.avento.api.dto.DocumentUploadResult;
import com.avento.service.DocumentReaderService;
import com.avento.service.WorkspaceAccessService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

class DocumentControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsSupportedDocumentAndRemovesTemporaryCopy() throws Exception {
        DocumentReaderService reader = reader();
        DocumentController controller = new DocumentController(reader, tempDir.toString(), 5000);
        MockMultipartFile upload = new MockMultipartFile("file", "notas.txt", "text/plain", "conteudo".getBytes());

        DocumentUploadResult result = controller.extract(upload).getBody().getData();

        assertEquals("notas.txt", result.name());
        assertEquals("conteudo", result.content());
        try (var files = Files.list(tempDir)) {
            assertFalse(files.findAny().isPresent());
        }
    }

    @Test
    void rejectsUnsupportedExtension() {
        DocumentController controller = new DocumentController(reader(), tempDir.toString(), 5000);
        MockMultipartFile upload =
                new MockMultipartFile("file", "programa.exe", "application/octet-stream", new byte[] {1});

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.extract(upload));

        assertEquals(415, error.getStatusCode().value());
    }

    private DocumentReaderService reader() {
        return new DocumentReaderService(
                new WorkspaceAccessService(),
                tempDir.resolve("missing-markitdown").toString(),
                Duration.ofSeconds(5),
                5000,
                DataSize.ofMegabytes(50));
    }
}
