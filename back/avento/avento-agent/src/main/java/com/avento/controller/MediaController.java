package com.avento.controller;

import com.avento.auth.security.AuthPrincipal;
import com.avento.model.GeneratedMediaAsset;
import com.avento.service.GeneratedMediaAssetService;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final GeneratedMediaAssetService assetService;
    private final Path mediaDirectory;

    public MediaController(
            GeneratedMediaAssetService assetService,
            @Value("${avento.media.directory:}") String configuredMediaDirectory) {
        this.assetService = assetService;
        this.mediaDirectory = configuredMediaDirectory == null || configuredMediaDirectory.isBlank()
                ? Paths.get(System.getProperty("user.home"), "Pictures", "Avento Generated Images")
                        .toAbsolutePath()
                        .normalize()
                : Paths.get(configuredMediaDirectory).toAbsolutePath().normalize();
    }

    @GetMapping
    public ResponseEntity<List<GeneratedMediaAsset>> listMedia(
            @RequestParam(name = "chatId", required = false) Long chatId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        if (chatId == null || principal == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        List<GeneratedMediaAsset> assets = assetService.listForChat(chatId, principal.userId());
        return ResponseEntity.ok(assets);
    }

    @GetMapping("/{filename:.+}")
    public ResponseEntity<Resource> serveMediaFile(@PathVariable("filename") String filename) {
        Path filePath = mediaDirectory.resolve(filename).normalize();
        if (!filePath.startsWith(mediaDirectory)) {
            return ResponseEntity.badRequest().build();
        }

        File file = filePath.toFile();
        if (!file.exists() || !file.isFile()) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(file);
        String contentType = "image/png";
        if (filename.toLowerCase().endsWith(".webp")) {
            contentType = "image/webp";
        } else if (filename.toLowerCase().endsWith(".jpg") || filename.toLowerCase().endsWith(".jpeg")) {
            contentType = "image/jpeg";
        } else if (filename.toLowerCase().endsWith(".mp4")) {
            contentType = "video/mp4";
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getName() + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }
}
