package com.avento.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.auth.model.UserRole;
import com.avento.auth.security.AuthPrincipal;
import com.avento.model.GeneratedMediaAsset;
import com.avento.repository.GeneratedMediaAssetRepository;
import com.avento.service.GeneratedMediaAssetService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Substitui o teste anterior, que cobria o registro de mídia "legada" a partir do texto das
 * mensagens — comportamento removido quando o MediaController passou a servir MediaAssetDto direto
 * do repositório. Aqui o que importa é a classificação por prefixo de arquivo: é ela que decide o
 * ícone/visualizador no grid do frontend, e um prefixo novo classificado errado passa despercebido.
 */
class MediaControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir
    Path mediaDirectory;

    @Test
    void classifiesEachManagedPrefixAndBuildsTheDownloadUrl() throws Exception {
        GeneratedMediaAssetRepository repository = mock(GeneratedMediaAssetRepository.class);
        List<GeneratedMediaAsset> assets = List.of(
                asset(1L, "avento-image-20260101-1.png"),
                asset(2L, "avento-video-20260101-1.webp"),
                asset(3L, "avento-doc-20260101-1.pdf"),
                asset(4L, "avento-mockup-20260101-1.html"));
        for (GeneratedMediaAsset asset : assets) {
            Files.writeString(mediaDirectory.resolve(asset.getFilename()), "conteudo");
        }
        when(repository.findByChatIdAndUserIdOrderByCreatedAtDesc(7L, USER_ID)).thenReturn(assets);
        MediaController controller = new MediaController(
                new GeneratedMediaAssetService(repository, mediaDirectory.toString()), mediaDirectory.toString());

        List<MediaController.MediaAssetDto> result =
                controller.listMedia(7L, principal()).getBody();

        assertThat(result)
                .extracting(MediaController.MediaAssetDto::type)
                .containsExactly("image", "video", "document", "artifact");
        assertThat(result.getFirst().url()).isEqualTo("/api/media/avento-image-20260101-1.png");
    }

    @Test
    void returnsEmptyListWithoutChatOrPrincipal() {
        GeneratedMediaAssetRepository repository = mock(GeneratedMediaAssetRepository.class);
        MediaController controller = new MediaController(
                new GeneratedMediaAssetService(repository, mediaDirectory.toString()), mediaDirectory.toString());

        assertThat(controller.listMedia(null, principal()).getBody()).isEmpty();
        assertThat(controller.listMedia(7L, null).getBody()).isEmpty();
    }

    private GeneratedMediaAsset asset(Long id, String filename) {
        GeneratedMediaAsset asset = new GeneratedMediaAsset();
        asset.setId(id);
        asset.setChatId(7L);
        asset.setUserId(USER_ID);
        asset.setFilename(filename);
        return asset;
    }

    private AuthPrincipal principal() {
        return new AuthPrincipal(
                USER_ID,
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "access-jti",
                "user@example.com",
                "User",
                UserRole.ROOT);
    }
}
