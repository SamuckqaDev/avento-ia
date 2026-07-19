package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.*;
import com.avento.api.dto.BaseResponse;
import com.avento.auth.security.AuthPrincipal;
import com.avento.model.GeneratedMediaAsset;
import com.avento.model.Message;
import com.avento.repository.ChatRepository;
import com.avento.repository.MessageRepository;
import com.avento.service.ChatArtifactService;
import com.avento.service.GeneratedMediaAssetService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final GeneratedMediaAssetService generatedMediaAssetService;
    private final ChatArtifactService chatArtifactService;
    private final ChatRepository chatRepository;
    private final MessageRepository messageRepository;
    private final Path mediaDirectory;

    public MediaController(
            GeneratedMediaAssetService generatedMediaAssetService,
            ChatArtifactService chatArtifactService,
            ChatRepository chatRepository,
            MessageRepository messageRepository,
            @Value("${avento.media.directory:}") String configuredMediaDirectory) {
        this.generatedMediaAssetService = generatedMediaAssetService;
        this.chatArtifactService = chatArtifactService;
        this.chatRepository = chatRepository;
        this.messageRepository = messageRepository;
        this.mediaDirectory = configuredMediaDirectory == null || configuredMediaDirectory.isBlank()
                ? Paths.get(System.getProperty("user.home"), "Pictures", "Avento Generated Images")
                        .toAbsolutePath()
                        .normalize()
                : Paths.get(configuredMediaDirectory).toAbsolutePath().normalize();
    }

    @GetMapping
    public ResponseEntity<BaseResponse<List<MediaItem>>> listMedia(
            @RequestParam Long chatId, @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null
                || chatRepository.findByIdAndUserId(chatId, principal.userId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found");
        }

        registerLegacyReferences(chatId, principal);
        List<MediaItem> media = generatedMediaAssetService.listForChat(chatId, principal.userId()).stream()
                .map(this::toMediaItem)
                .toList();
        return ApiResponses.ok(media);
    }

    @GetMapping("/{filename:.+}")
    public ResponseEntity<Resource> getMedia(@PathVariable String filename) throws IOException {
        Path file = mediaDirectory.resolve(filename).normalize();
        if (!file.startsWith(mediaDirectory) || !isGeneratedMedia(file)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new UrlResource(file.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .contentType(contentTypeFor(file))
                .body(resource);
    }

    private void registerLegacyReferences(Long chatId, AuthPrincipal principal) {
        List<Message> messages = messageRepository.findByChatIdOrderByTimestampAsc(chatId);
        for (String filename : chatArtifactService.referencedMediaFilenames(messages)) {
            Path file = mediaDirectory.resolve(filename).normalize();
            if (!file.startsWith(mediaDirectory) || !isGeneratedMedia(file)) {
                continue;
            }
            try {
                generatedMediaAssetService.register(
                        file, chatId, principal.userId(), filename.endsWith(".webp") ? "video" : "image");
            } catch (IllegalArgumentException ignored) {
                // Uma referência textual antiga não pode transferir uma mídia já vinculada.
            }
        }
    }

    private MediaType contentTypeFor(Path path) {
        String name = path.getFileName().toString();
        if (name.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        if (name.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        return MediaType.IMAGE_PNG;
    }

    private boolean isGeneratedMedia(Path path) {
        String filename = path.getFileName().toString();
        return Files.isRegularFile(path)
                && ((filename.startsWith("avento-image-") && filename.endsWith(".png"))
                        || (filename.startsWith("avento-video-") && filename.endsWith(".webp"))
                        || (filename.startsWith("avento-doc-") && filename.endsWith(".pdf")));
    }

    private MediaItem toMediaItem(GeneratedMediaAsset asset) {
        Instant createdAt = asset.getCreatedAt() == null
                ? Instant.EPOCH
                : asset.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant();
        return new MediaItem(
                asset.getId().toString(),
                "/api/media/" + asset.getFilename(),
                asset.getFilename(),
                createdAt.toString());
    }
}
