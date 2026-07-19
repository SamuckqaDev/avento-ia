package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.model.GeneratedMediaAsset;
import com.avento.repository.GeneratedMediaAssetRepository;
import com.avento.service.dto.AssetDeletionResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedMediaAssetServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir
    Path mediaDirectory;

    @Test
    void registersGeneratedFileAgainstItsChat() throws Exception {
        GeneratedMediaAssetRepository repository = mock(GeneratedMediaAssetRepository.class);
        Path image = Files.writeString(mediaDirectory.resolve("avento-image-test.png"), "image");
        when(repository.findByFilename("avento-image-test.png")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(GeneratedMediaAsset.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        GeneratedMediaAssetService service = new GeneratedMediaAssetService(repository, mediaDirectory.toString());

        GeneratedMediaAsset asset = service.register(image, 7L, USER_ID, "image");

        assertThat(asset.getChatId()).isEqualTo(7L);
        assertThat(asset.getUserId()).isEqualTo(USER_ID);
        assertThat(asset.getFilename()).isEqualTo("avento-image-test.png");
        assertThat(asset.getMediaType()).isEqualTo("image");
    }

    @Test
    void resolvesLatestGeneratedImageForVideoReference() throws Exception {
        GeneratedMediaAssetRepository repository = mock(GeneratedMediaAssetRepository.class);
        Path image = Files.writeString(mediaDirectory.resolve("avento-image-latest.png"), "image");
        GeneratedMediaAsset asset = new GeneratedMediaAsset();
        asset.setChatId(7L);
        asset.setUserId(USER_ID);
        asset.setFilename(image.getFileName().toString());
        asset.setMediaType("image");
        when(repository.findFirstByChatIdAndUserIdAndMediaTypeOrderByIdDesc(7L, USER_ID, "image"))
                .thenReturn(Optional.of(asset));
        GeneratedMediaAssetService service = new GeneratedMediaAssetService(repository, mediaDirectory.toString());

        assertThat(service.latestImageForChat(7L, USER_ID)).contains(image);
    }

    @Test
    void deletesDatabaseRecordsAndPhysicalFilesForChat() throws Exception {
        GeneratedMediaAssetRepository repository = mock(GeneratedMediaAssetRepository.class);
        Path image = Files.writeString(mediaDirectory.resolve("avento-image-delete.png"), "image");
        GeneratedMediaAsset asset = new GeneratedMediaAsset();
        asset.setChatId(7L);
        asset.setUserId(USER_ID);
        asset.setFilename(image.getFileName().toString());
        asset.setMediaType("image");
        when(repository.findByChatIdAndUserIdOrderByCreatedAtDesc(7L, USER_ID)).thenReturn(List.of(asset));
        GeneratedMediaAssetService service = new GeneratedMediaAssetService(repository, mediaDirectory.toString());

        AssetDeletionResult result = service.deleteForChat(7L, USER_ID);

        assertThat(result.deletedAssets()).isEqualTo(1);
        assertThat(result.deletedFiles()).isEqualTo(1);
        assertThat(image).doesNotExist();
        verify(repository).deleteAllInBatch(List.of(asset));
    }
}
