package com.avento.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.api.dto.MediaItem;
import com.avento.auth.model.UserRole;
import com.avento.auth.security.AuthPrincipal;
import com.avento.model.Chat;
import com.avento.model.GeneratedMediaAsset;
import com.avento.model.Message;
import com.avento.repository.ChatRepository;
import com.avento.repository.GeneratedMediaAssetRepository;
import com.avento.repository.MessageRepository;
import com.avento.service.ChatArtifactService;
import com.avento.service.GeneratedMediaAssetService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir
    Path mediaDirectory;

    @Test
    void listsLegacyLocalMediaForTheSelectedChat() throws Exception {
        GeneratedMediaAssetRepository mediaRepository = mock(GeneratedMediaAssetRepository.class);
        AtomicReference<GeneratedMediaAsset> storedAsset = new AtomicReference<>();
        when(mediaRepository.findByFilename("avento-image-legacy.png")).thenReturn(Optional.empty());
        when(mediaRepository.saveAndFlush(any(GeneratedMediaAsset.class))).thenAnswer(invocation -> {
            GeneratedMediaAsset saved = invocation.getArgument(0);
            saved.setId(10L);
            storedAsset.set(saved);
            return saved;
        });
        when(mediaRepository.findByChatIdAndUserIdOrderByCreatedAtDesc(7L, USER_ID))
                .thenAnswer(invocation -> List.of(storedAsset.get()));
        GeneratedMediaAssetService mediaService =
                new GeneratedMediaAssetService(mediaRepository, mediaDirectory.toString());
        ChatRepository chatRepository = mock(ChatRepository.class);
        MessageRepository messageRepository = mock(MessageRepository.class);
        Chat chat = new Chat("Imagens", "");
        chat.setId(7L);
        chat.setUserId(USER_ID);
        Path image = Files.writeString(mediaDirectory.resolve("avento-image-legacy.png"), "image");
        Message message = new Message(7L, "assistant", "Imagem em " + image);
        when(chatRepository.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(chat));
        when(messageRepository.findByChatIdOrderByTimestampAsc(7L)).thenReturn(List.of(message));
        MediaController controller = new MediaController(
                mediaService,
                new ChatArtifactService(mediaDirectory.toString()),
                chatRepository,
                messageRepository,
                mediaDirectory.toString());

        List<MediaItem> result = controller.listMedia(7L, principal()).getBody().getData();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().url()).isEqualTo("/api/media/avento-image-legacy.png");
        assertThat(storedAsset.get().getChatId()).isEqualTo(7L);
        assertThat(storedAsset.get().getUserId()).isEqualTo(USER_ID);
    }

    private AuthPrincipal principal() {
        return new AuthPrincipal(
                USER_ID,
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "access-jti",
                "user@example.com",
                "User",
                UserRole.ROOT);
    }
}
