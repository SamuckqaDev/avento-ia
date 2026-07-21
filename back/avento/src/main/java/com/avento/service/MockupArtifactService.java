package com.avento.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Persists interactive UI mockups (```ui-preview blocks) that arrive inside an assistant message as
 * downloadable chat artifacts. The block keeps rendering inline in the chat; this stores an extra
 * `.html` copy in the media directory so it shows up in the Media & Artifacts panel and can be
 * downloaded on demand. Files are named `avento-mockup-*.html` and cleaned up with the chat, like
 * the other generated media.
 */
@Service
public class MockupArtifactService {

    private static final Logger logger = LoggerFactory.getLogger(MockupArtifactService.class);
    private static final Pattern UI_PREVIEW_BLOCK = Pattern.compile("(?s)```ui-preview\\s*\\n(.*?)```");

    private final GeneratedMediaAssetService generatedMediaAssetService;
    private final Path mediaDirectory;

    public MockupArtifactService(
            GeneratedMediaAssetService generatedMediaAssetService,
            @Value("${avento.media.directory:}") String configuredMediaDirectory) {
        this.generatedMediaAssetService = generatedMediaAssetService;
        this.mediaDirectory = configuredMediaDirectory == null || configuredMediaDirectory.isBlank()
                ? Paths.get(System.getProperty("user.home"), "Pictures", "Avento Generated Images")
                        .toAbsolutePath()
                        .normalize()
                : Paths.get(configuredMediaDirectory).toAbsolutePath().normalize();
    }

    /**
     * Extracts every ui-preview block from an assistant message and stores each as an artifact.
     * Never throws: a failure to persist an artifact must not break saving the message.
     *
     * @return the filenames created (empty when there is nothing to persist).
     */
    public List<String> persistFromMessage(Long chatId, UUID userId, String role, String content) {
        List<String> created = new ArrayList<>();
        if (chatId == null || userId == null || !"assistant".equalsIgnoreCase(role) || content == null) {
            return created;
        }
        Matcher matcher = UI_PREVIEW_BLOCK.matcher(content);
        int index = 0;
        while (matcher.find()) {
            String html = matcher.group(1).strip();
            if (html.isBlank()) {
                continue;
            }
            index++;
            try {
                Files.createDirectories(mediaDirectory);
                String filename = "avento-mockup-" + UUID.randomUUID() + "-" + index + ".html";
                Path out = mediaDirectory.resolve(filename);
                Path temporary = Files.createTempFile(mediaDirectory, ".avento-mockup-", ".tmp");
                Files.writeString(temporary, html, StandardCharsets.UTF_8);
                moveAtomically(temporary, out);
                generatedMediaAssetService.register(out, chatId, userId, "artifact");
                created.add(filename);
            } catch (IOException | RuntimeException exception) {
                logger.warn("Could not persist ui-preview artifact for chat {}: {}", chatId, exception.getMessage());
            }
        }
        return created;
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnavailable) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
