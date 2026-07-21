package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.BaseResponse;
import com.avento.api.dto.MediaItem;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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

    @GetMapping("/assets/{assetId}")
    public ResponseEntity<Resource> getMediaById(
            @PathVariable Long assetId, @AuthenticationPrincipal AuthPrincipal principal) throws IOException {
        GeneratedMediaAsset asset = generatedMediaAssetService
                .findOwnedById(assetId, principal.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Media not found"));
        return mediaResponse(asset);
    }

    @GetMapping("/{filename:.+}")
    public ResponseEntity<Resource> getMedia(
            @PathVariable String filename, @AuthenticationPrincipal AuthPrincipal principal) throws IOException {
        GeneratedMediaAsset asset = generatedMediaAssetService
                .findOwnedByFilename(filename, principal.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Media not found"));
        return mediaResponse(asset);
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
        if (name.endsWith(".html")) return MediaType.TEXT_PLAIN;
        return MediaType.IMAGE_PNG;
    }

    @GetMapping("/chat/{chatId}/artifacts.zip")
    public ResponseEntity<StreamingResponseBody> downloadArtifactsZip(
            @PathVariable Long chatId, @AuthenticationPrincipal AuthPrincipal principal) throws IOException {
        if (principal == null
                || chatRepository.findByIdAndUserId(chatId, principal.userId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found");
        }

        List<GeneratedMediaAsset> artifacts =
                generatedMediaAssetService.listForChat(chatId, principal.userId()).stream()
                        .filter(asset -> {
                            String name = asset.getFilename();
                            return name.startsWith("avento-mockup-") || name.startsWith("avento-doc-");
                        })
                        .toList();
        if (artifacts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No downloadable artifacts for this chat");
        }

        StreamingResponseBody resource = outputStream -> {
            try (ZipOutputStream zip = new ZipOutputStream(outputStream)) {
                for (GeneratedMediaAsset asset : artifacts) {
                    Path file =
                            generatedMediaAssetService.resolveOwnedPath(asset).orElse(null);
                    if (file == null) {
                        continue;
                    }
                    zip.putNextEntry(new ZipEntry(asset.getFilename()));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(
                        "Content-Disposition",
                        ContentDisposition.attachment()
                                .filename("avento-chat-" + chatId + "-artifacts.zip")
                                .build()
                                .toString())
                .body(resource);
    }

    private boolean isGeneratedMedia(Path path) {
        String filename = path.getFileName().toString();
        return Files.isRegularFile(path)
                && ((filename.startsWith("avento-image-") && filename.endsWith(".png"))
                        || (filename.startsWith("avento-video-") && filename.endsWith(".webp"))
                        || (filename.startsWith("avento-doc-") && filename.endsWith(".pdf"))
                        || (filename.startsWith("avento-mockup-") && filename.endsWith(".html")));
    }

    private MediaItem toMediaItem(GeneratedMediaAsset asset) {
        Instant createdAt = asset.getCreatedAt() == null
                ? Instant.EPOCH
                : asset.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant();
        return new MediaItem(
                asset.getId().toString(),
                "/api/media/assets/" + asset.getId(),
                asset.getFilename(),
                mediaTypeOf(asset.getFilename()),
                createdAt.toString());
    }

    private ResponseEntity<Resource> mediaResponse(GeneratedMediaAsset asset) throws IOException {
        Path file = generatedMediaAssetService
                .resolveOwnedPath(asset)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Media not found"));
        Resource resource = new UrlResource(file.toUri());
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(contentTypeFor(file));
        if (asset.getFilename().endsWith(".html")) {
            response.header("Content-Security-Policy", "sandbox; default-src 'none'");
            response.header(
                    "Content-Disposition",
                    ContentDisposition.attachment()
                            .filename(asset.getFilename())
                            .build()
                            .toString());
        }
        return response.body(resource);
    }

    private String mediaTypeOf(String filename) {
        if (filename.startsWith("avento-video-")) return "video";
        if (filename.startsWith("avento-doc-")) return "document";
        if (filename.startsWith("avento-mockup-")) return "artifact";
        return "image";
    }
}
