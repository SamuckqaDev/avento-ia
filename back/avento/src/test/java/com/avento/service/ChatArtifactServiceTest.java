package com.avento.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.model.Message;
import com.avento.service.dto.ArtifactDeletionResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatArtifactServiceTest {

    @TempDir
    Path mediaDirectory;

    @Test
    void deletesOnlyManagedMediaReferencedByChatMessages() throws Exception {
        Path generatedImage = Files.writeString(mediaDirectory.resolve("avento-image-chat.png"), "image");
        Path generatedVideo = Files.writeString(mediaDirectory.resolve("avento-video-chat.webp"), "video");
        Path unrelatedImage = Files.writeString(mediaDirectory.resolve("avento-image-other.png"), "other");
        Path unsafeFile = Files.writeString(mediaDirectory.resolve("notes.txt"), "keep");
        Message message = new Message(
                7L,
                "assistant",
                "![Imagem](/api/media/avento-image-chat.png)\n"
                        + "![Vídeo](/api/media/avento-video-chat.webp)\n"
                        + "[Arquivo](/api/media/notes.txt)");

        ArtifactDeletionResult result =
                new ChatArtifactService(mediaDirectory.toString()).deleteOwnedArtifacts(List.of(message));

        assertEquals(2, result.referencedFiles());
        assertEquals(2, result.deletedFiles());
        assertFalse(Files.exists(generatedImage));
        assertFalse(Files.exists(generatedVideo));
        assertTrue(Files.exists(unrelatedImage));
        assertTrue(Files.exists(unsafeFile));
    }

    @Test
    void acceptsMissingLegacyMediaWithoutBlockingChatDeletion() {
        Message message = new Message(7L, "assistant", "![](/api/media/avento-image-missing.png)");

        ArtifactDeletionResult result =
                new ChatArtifactService(mediaDirectory.toString()).deleteOwnedArtifacts(List.of(message));

        assertEquals(1, result.referencedFiles());
        assertEquals(0, result.deletedFiles());
    }

    @Test
    void deletesLegacyMediaReferencedByItsLocalPath() throws Exception {
        Path generatedImage = Files.writeString(mediaDirectory.resolve("avento-image-legacy.png"), "image");
        Message message = new Message(
                7L,
                "assistant",
                "Imagem salva em /Users/test/Pictures/Avento Generated Images/avento-image-legacy.png");

        ArtifactDeletionResult result =
                new ChatArtifactService(mediaDirectory.toString()).deleteOwnedArtifacts(List.of(message));

        assertEquals(1, result.referencedFiles());
        assertEquals(1, result.deletedFiles());
        assertFalse(Files.exists(generatedImage));
    }
}
