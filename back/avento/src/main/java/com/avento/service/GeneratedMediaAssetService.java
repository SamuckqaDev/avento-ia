package com.avento.service;

import com.avento.model.GeneratedMediaAsset;
import com.avento.repository.GeneratedMediaAssetRepository;
import com.avento.service.dto.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GeneratedMediaAssetService {

    private final GeneratedMediaAssetRepository repository;
    private final Path mediaDirectory;

    public GeneratedMediaAssetService(
            GeneratedMediaAssetRepository repository,
            @Value("${avento.media.directory:}") String configuredMediaDirectory) {
        this.repository = repository;
        this.mediaDirectory = configuredMediaDirectory == null || configuredMediaDirectory.isBlank()
                ? Paths.get(System.getProperty("user.home"), "Pictures", "Avento Generated Images")
                        .toAbsolutePath()
                        .normalize()
                : Paths.get(configuredMediaDirectory).toAbsolutePath().normalize();
    }

    public GeneratedMediaAsset register(Path outputPath, Long chatId, UUID userId, String mediaType) {
        if (outputPath == null || chatId == null || userId == null) {
            throw new IllegalArgumentException("A mídia gerada precisa estar vinculada a um chat autenticado.");
        }
        Path normalized = outputPath.toAbsolutePath().normalize();
        if (!normalized.startsWith(mediaDirectory)
                || !Files.isRegularFile(normalized)
                || !isManagedMedia(normalized.getFileName().toString())) {
            throw new IllegalArgumentException("Arquivo fora da pasta de mídias gerenciadas.");
        }

        String filename = normalized.getFileName().toString();
        GeneratedMediaAsset asset = repository.findByFilename(filename).orElseGet(GeneratedMediaAsset::new);
        if (asset.getUserId() != null && (!userId.equals(asset.getUserId()) || !chatId.equals(asset.getChatId()))) {
            throw new IllegalArgumentException("A mídia já pertence a outra conversa.");
        }
        asset.setChatId(chatId);
        asset.setUserId(userId);
        asset.setFilename(filename);
        asset.setMediaType(mediaType);
        return repository.saveAndFlush(asset);
    }

    public List<GeneratedMediaAsset> listForChat(Long chatId, UUID userId) {
        return repository.findByChatIdAndUserIdOrderByCreatedAtDesc(chatId, userId).stream()
                .filter(asset -> Files.isRegularFile(
                        mediaDirectory.resolve(asset.getFilename()).normalize()))
                .toList();
    }

    public Optional<Path> latestImageForChat(Long chatId, UUID userId) {
        return repository
                .findFirstByChatIdAndUserIdAndMediaTypeOrderByIdDesc(chatId, userId, "image")
                .map(GeneratedMediaAsset::getFilename)
                .map(mediaDirectory::resolve)
                .map(Path::normalize)
                .filter(path -> path.startsWith(mediaDirectory) && Files.isRegularFile(path));
    }

    public AssetDeletionResult deleteForChat(Long chatId, UUID userId) {
        List<GeneratedMediaAsset> assets = repository.findByChatIdAndUserIdOrderByCreatedAtDesc(chatId, userId);
        int deletedFiles = 0;
        for (GeneratedMediaAsset asset : assets) {
            Path file = mediaDirectory.resolve(asset.getFilename()).normalize();
            if (!file.startsWith(mediaDirectory) || !isManagedMedia(asset.getFilename())) {
                continue;
            }
            try {
                if (Files.deleteIfExists(file)) {
                    deletedFiles++;
                }
            } catch (IOException exception) {
                throw new MediaAssetDeletionException(asset.getFilename(), exception);
            }
        }
        repository.deleteAllInBatch(assets);
        return new AssetDeletionResult(assets.size(), deletedFiles);
    }

    private boolean isManagedMedia(String filename) {
        return (filename.startsWith("avento-image-") && filename.endsWith(".png"))
                || (filename.startsWith("avento-video-") && filename.endsWith(".webp"));
    }

    public static class MediaAssetDeletionException extends RuntimeException {
        private final String filename;

        public MediaAssetDeletionException(String filename, IOException cause) {
            super("Could not delete generated media " + filename, cause);
            this.filename = filename;
        }

        public String getFilename() {
            return filename;
        }
    }
}
