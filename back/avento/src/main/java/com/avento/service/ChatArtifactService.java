package com.avento.service;

import com.avento.model.Message;
import com.avento.service.dto.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatArtifactService {

    private static final Pattern MEDIA_REFERENCE = Pattern.compile(
            "(?:/api/media/|(?:^|[/\\\\]))((?:avento-image-|avento-video-)[A-Za-z0-9._-]+(?:\\.png|\\.webp))",
            Pattern.MULTILINE);

    private final Path mediaDirectory;

    public ChatArtifactService(@Value("${avento.media.directory:}") String configuredMediaDirectory) {
        this.mediaDirectory = configuredMediaDirectory == null || configuredMediaDirectory.isBlank()
                ? Paths.get(System.getProperty("user.home"), "Pictures", "Avento Generated Images")
                        .toAbsolutePath()
                        .normalize()
                : Paths.get(configuredMediaDirectory).toAbsolutePath().normalize();
    }

    public ArtifactDeletionResult deleteOwnedArtifacts(List<Message> messages) {
        Set<String> filenames = referencedMediaFilenames(messages);
        int deletedFiles = 0;

        for (String filename : filenames) {
            if (!isManagedMedia(filename)) {
                continue;
            }
            Path mediaFile = mediaDirectory.resolve(filename).normalize();
            if (!mediaFile.startsWith(mediaDirectory)) {
                continue;
            }
            try {
                if (Files.deleteIfExists(mediaFile)) {
                    deletedFiles++;
                }
            } catch (IOException exception) {
                throw new ChatArtifactDeletionException(filename, exception);
            }
        }

        return new ArtifactDeletionResult(filenames.size(), deletedFiles);
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

    public static class ChatArtifactDeletionException extends RuntimeException {

        private final String filename;

        public ChatArtifactDeletionException(String filename, IOException cause) {
            super("Could not delete chat artifact " + filename, cause);
            this.filename = filename;
        }

        public String getFilename() {
            return filename;
        }
    }
}
