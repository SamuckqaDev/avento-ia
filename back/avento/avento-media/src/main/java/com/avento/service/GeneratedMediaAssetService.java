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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GeneratedMediaAssetService {

    private static final Logger logger = LoggerFactory.getLogger(GeneratedMediaAssetService.class);

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

    public Optional<GeneratedMediaAsset> findOwnedById(Long assetId, UUID userId) {
        if (assetId == null || userId == null) {
            return Optional.empty();
        }
        return repository.findByIdAndUserId(assetId, userId);
    }

    public Optional<GeneratedMediaAsset> findOwnedByFilename(String filename, UUID userId) {
        if (filename == null || filename.isBlank() || userId == null) {
            return Optional.empty();
        }
        return repository.findByFilenameAndUserId(filename, userId);
    }

    public Optional<Path> resolveOwnedPath(GeneratedMediaAsset asset) {
        if (asset == null || !isManagedMedia(asset.getFilename())) {
            return Optional.empty();
        }
        Path file = mediaDirectory.resolve(asset.getFilename()).normalize();
        return file.startsWith(mediaDirectory) && Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
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
        int failedFiles = 0;
        for (GeneratedMediaAsset asset : assets) {
            Path file = mediaDirectory.resolve(asset.getFilename()).normalize();
            if (!file.startsWith(mediaDirectory) || !isManagedMedia(asset.getFilename())) {
                continue;
            }
            // Best effort: a locked file must not abort the deletion and leave the chat half-erased.
            try {
                if (Files.deleteIfExists(file)) {
                    deletedFiles++;
                }
            } catch (IOException exception) {
                failedFiles++;
                logger.warn("Could not delete generated media {} of chat {}", asset.getFilename(), chatId, exception);
            }
        }
        repository.deleteAllInBatch(assets);
        return new AssetDeletionResult(assets.size(), deletedFiles, failedFiles);
    }

    public boolean isManagedMedia(String filename) {
        return (filename.startsWith("avento-image-") && filename.endsWith(".png"))
                || (filename.startsWith("avento-video-") && filename.endsWith(".webp"))
                || (filename.startsWith("avento-doc-") && filename.endsWith(".pdf"))
                || (filename.startsWith("avento-mockup-") && filename.endsWith(".html"));
    }
}
