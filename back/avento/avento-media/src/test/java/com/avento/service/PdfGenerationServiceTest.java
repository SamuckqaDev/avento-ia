package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfGenerationServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private PdfGenerationService service(Path mediaDir, GeneratedMediaAssetService assets) throws Exception {
        PdfGenerationService service = new PdfGenerationService(assets, mapper);
        Field dir = PdfGenerationService.class.getDeclaredField("mediaDirectoryPath");
        dir.setAccessible(true);
        dir.set(service, mediaDir.toString());
        return service;
    }

    @Test
    void rendersMarkdownWithATableIntoANonEmptyPdfAndRegistersTheAsset(@TempDir Path tempDir) throws Exception {
        GeneratedMediaAssetService assets = mock(GeneratedMediaAssetService.class);
        PdfGenerationService service = service(tempDir, assets);
        Long chatId = 7L;
        UUID userId = UUID.randomUUID();

        ObjectNode result = service.generate(
                "Relatório de teste",
                "# Título\n\nTexto.\n\n| Nome | Ano |\n|------|-----|\n| React | 2013 |\n| Vue | 2014 |",
                null,
                chatId,
                userId);

        assertThat(result.path("status").asText()).isEqualTo("success");
        assertThat(result.path("mediaType").asText()).isEqualTo("document");
        String filename = result.path("filename").asText();
        assertThat(filename).startsWith("avento-doc-").endsWith(".pdf");

        Path pdf = tempDir.resolve(filename);
        assertThat(Files.exists(pdf)).isTrue();
        assertThat(Files.size(pdf)).isGreaterThan(0L);
        // Assinatura de arquivo PDF: começa com "%PDF".
        assertThat(Files.readAllBytes(pdf)).startsWith('%', 'P', 'D', 'F');

        verify(assets).register(eq(pdf), eq(chatId), eq(userId), eq("document"));
    }

    @Test
    void acceptsRawHtmlWhenNoMarkdownIsProvided(@TempDir Path tempDir) throws Exception {
        GeneratedMediaAssetService assets = mock(GeneratedMediaAssetService.class);
        when(assets.register(any(), any(), any(), any())).thenReturn(null);
        PdfGenerationService service = service(tempDir, assets);

        ObjectNode result =
                service.generate("Doc HTML", null, "<p>conteudo <strong>html</strong></p>", 1L, UUID.randomUUID());

        assertThat(result.path("status").asText()).isEqualTo("success");
        Path pdf = tempDir.resolve(result.path("filename").asText());
        assertThat(Files.size(pdf)).isGreaterThan(0L);
    }
}
