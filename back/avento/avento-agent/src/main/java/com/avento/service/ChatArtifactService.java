package com.avento.service;

import com.avento.model.GeneratedMediaAsset;
import com.avento.model.Message;
import com.avento.repository.GeneratedMediaAssetRepository;
import com.avento.service.dto.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatArtifactService {

    private static final Logger logger = LoggerFactory.getLogger(ChatArtifactService.class);

    private static final Pattern MEDIA_REFERENCE = Pattern.compile(
            "(?:/api/media/|(?:^|[/\\\\]))((?:avento-image-|avento-video-)[A-Za-z0-9._-]+(?:\\.png|\\.webp))",
            Pattern.MULTILINE);

    private final Path mediaDirectory;

    // Ownership check: the media directory is shared by every chat, so a filename found in text
    // is not proof of ownership. generated_media_assets is the only authority on who owns a file.
    private final GeneratedMediaAssetRepository assetRepository;

    public ChatArtifactService(String configuredMediaDirectory) {
        this(configuredMediaDirectory, (GeneratedMediaAssetRepository) null);
    }

    @Autowired
    public ChatArtifactService(
            @Value("${avento.media.directory:}") String configuredMediaDirectory,
            ObjectProvider<GeneratedMediaAssetRepository> assetRepositoryProvider) {
        this(configuredMediaDirectory, assetRepositoryProvider.getIfAvailable());
    }

    public ChatArtifactService(String configuredMediaDirectory, GeneratedMediaAssetRepository assetRepository) {
        this.assetRepository = assetRepository;
        this.mediaDirectory = configuredMediaDirectory == null || configuredMediaDirectory.isBlank()
                ? Paths.get(System.getProperty("user.home"), "Pictures", "Avento Generated Images")
                        .toAbsolutePath()
                        .normalize()
                : Paths.get(configuredMediaDirectory).toAbsolutePath().normalize();
    }

    /**
     * Deletes the legacy media of a chat — files referenced only as text, from before
     * generated_media_assets existed. A file registered to a different chat is always kept: the
     * deleted chat merely mentioned its name (the assistant writes the path in its answers), and
     * deleting it here used to wipe media that other chats still displayed.
     */
    public ArtifactDeletionResult deleteOwnedArtifacts(List<Message> messages, Long chatId) {
        Set<String> filenames = referencedMediaFilenames(messages);
        int deletedFiles = 0;
        int keptFiles = 0;
        int failedFiles = 0;

        for (String filename : filenames) {
            if (!isManagedMedia(filename)) {
                continue;
            }
            Path mediaFile = mediaDirectory.resolve(filename).normalize();
            if (!mediaFile.startsWith(mediaDirectory)) {
                continue;
            }
            if (belongsToAnotherChat(filename, chatId)) {
                keptFiles++;
                continue;
            }
            // Best effort: a locked file must not abort the deletion and leave the chat half-erased.
            try {
                if (Files.deleteIfExists(mediaFile)) {
                    deletedFiles++;
                }
            } catch (IOException exception) {
                failedFiles++;
                logger.warn("Could not delete media {} of chat {}", filename, chatId, exception);
            }
        }

        return new ArtifactDeletionResult(filenames.size(), deletedFiles, keptFiles, failedFiles);
    }

    private boolean belongsToAnotherChat(String filename, Long chatId) {
        if (assetRepository == null) {
            return false;
        }
        return assetRepository
                .findByFilename(filename)
                .map(GeneratedMediaAsset::getChatId)
                .filter(owner -> !Objects.equals(owner, chatId))
                .map(owner -> {
                    logger.info(
                            "Keeping media {} mentioned by chat {}: it belongs to chat {}", filename, chatId, owner);
                    return true;
                })
                .orElse(false);
    }

    public Set<String> referencedMediaFilenames(List<Message> messages) {
        Set<String> filenames = new LinkedHashSet<>();
        for (Message message : messages == null ? List.<Message>of() : messages) {
            String content = message.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            Matcher matcher = MEDIA_REFERENCE.matcher(content);
            while (matcher.find()) {
                filenames.add(matcher.group(1));
            }
        }
        return filenames;
    }

    private boolean isManagedMedia(String filename) {
        return (filename.startsWith("avento-image-") && filename.endsWith(".png"))
                || (filename.startsWith("avento-video-") && filename.endsWith(".webp"));
    }
}
