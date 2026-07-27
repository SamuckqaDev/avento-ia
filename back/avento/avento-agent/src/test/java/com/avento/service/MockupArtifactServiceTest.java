package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MockupArtifactServiceTest {

    private MockupArtifactService service(Path mediaDir, GeneratedMediaAssetService assets) throws Exception {
        MockupArtifactService service = new MockupArtifactService(assets, mediaDir.toString());
        Field dir = MockupArtifactService.class.getDeclaredField("mediaDirectory");
        dir.setAccessible(true);
        dir.set(service, mediaDir);
        return service;
    }

    @Test
    void extractsUiPreviewBlockFromAssistantMessageAndRegistersAnArtifact(@TempDir Path tempDir) throws Exception {
        GeneratedMediaAssetService assets = mock(GeneratedMediaAssetService.class);
        MockupArtifactService service = service(tempDir, assets);
        UUID userId = UUID.randomUUID();
        String content =
                "Segue a tela:\n\n```ui-preview\n<!doctype html><html><body><h1>Login</h1></body></html>\n```\n";

        List<String> created = service.persistFromMessage(9L, userId, "assistant", content);

        assertThat(created).hasSize(1);
        String filename = created.get(0);
        assertThat(filename).startsWith("avento-mockup-").endsWith(".html");
        Path file = tempDir.resolve(filename);
        assertThat(Files.readString(file)).contains("<h1>Login</h1>");
        verify(assets).register(eq(file), eq(9L), eq(userId), eq("artifact"));
    }

    @Test
    void extractsEveryUiPreviewBlockWhenTheMessageHasSeveral(@TempDir Path tempDir) throws Exception {
        GeneratedMediaAssetService assets = mock(GeneratedMediaAssetService.class);
        MockupArtifactService service = service(tempDir, assets);
        String content = "```ui-preview\n<html>a</html>\n```\ntexto\n```ui-preview\n<html>b</html>\n```";

        List<String> created = service.persistFromMessage(1L, UUID.randomUUID(), "assistant", content);

        assertThat(created).hasSize(2);
    }

    @Test
    void ignoresUserMessagesAndMessagesWithoutAPreviewBlock(@TempDir Path tempDir) throws Exception {
        GeneratedMediaAssetService assets = mock(GeneratedMediaAssetService.class);
        MockupArtifactService service = service(tempDir, assets);

        assertThat(service.persistFromMessage(1L, UUID.randomUUID(), "user", "```ui-preview\n<html/>\n```"))
                .isEmpty();
        assertThat(service.persistFromMessage(1L, UUID.randomUUID(), "assistant", "só texto, sem preview"))
                .isEmpty();
        verify(assets, never()).register(any(), any(), any(), any());
    }
}
