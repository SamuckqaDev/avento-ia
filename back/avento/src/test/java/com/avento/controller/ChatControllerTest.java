package com.avento.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.api.dto.ChatDeletionResult;
import com.avento.auth.model.UserRole;
import com.avento.auth.security.AuthPrincipal;
import com.avento.controller.dto.ChatDtos.ChatCreateRequest;
import com.avento.controller.dto.ChatDtos.ChatResponse;
import com.avento.controller.dto.ChatDtos.ChatUpdateRequest;
import com.avento.controller.dto.ChatDtos.MessageCreateRequest;
import com.avento.model.Chat;
import com.avento.model.Message;
import com.avento.repository.ChatRepository;
import com.avento.repository.MessageRepository;
import com.avento.service.ChatArtifactService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class ChatControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path mediaDirectory;

    @Test
    void updatesChatProjectContext() {
        ChatRepository chatRepository = mock(ChatRepository.class);
        MessageRepository messageRepository = mock(MessageRepository.class);
        ChatController controller = new ChatController(chatRepository, messageRepository, artifactService());
        Chat chat = new Chat("Projeto", "");
        chat.setId(7L);
        chat.setUserId(USER_ID);
        AuthPrincipal principal = principal(USER_ID);
        when(chatRepository.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(chat));
        when(chatRepository.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChatUpdateRequest payload = new ChatUpdateRequest(null, "/Users/me/app|/Users/me/api");

        ChatResponse updated =
                controller.updateChat(7L, payload, principal).getBody().getData();

        assertEquals("/Users/me/app|/Users/me/api", updated.projectPath());
        assertNotNull(updated.updatedAt());
        verify(chatRepository).save(chat);
    }

    @Test
    void savingMessageTouchesChatTimestamp() {
        ChatRepository chatRepository = mock(ChatRepository.class);
        MessageRepository messageRepository = mock(MessageRepository.class);
        ChatController controller = new ChatController(chatRepository, messageRepository, artifactService());
        Chat chat = new Chat("Projeto", "/Users/me/app");
        chat.setId(7L);
        chat.setUserId(USER_ID);
        chat.setUpdatedAt(LocalDateTime.now().minusDays(1));
        AuthPrincipal principal = principal(USER_ID);
        when(chatRepository.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(chat));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatRepository.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MessageCreateRequest message = new MessageCreateRequest("user", "oi", null, null);

        controller.addMessage(7L, message, principal);

        ArgumentCaptor<Chat> chatCaptor = ArgumentCaptor.forClass(Chat.class);
        verify(chatRepository).save(chatCaptor.capture());
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(messageCaptor.capture());
        assertEquals(7L, messageCaptor.getValue().getChatId());
        assertNotNull(chatCaptor.getValue().getUpdatedAt());
    }

    @Test
    void createChatAssignsAuthenticatedOwner() {
        ChatRepository chatRepository = mock(ChatRepository.class);
        MessageRepository messageRepository = mock(MessageRepository.class);
        ChatController controller = new ChatController(chatRepository, messageRepository, artifactService());
        when(chatRepository.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChatCreateRequest payload = new ChatCreateRequest("Minha conversa", "/Users/me/app");
        controller.createChat(payload, principal(USER_ID));

        ArgumentCaptor<Chat> chatCaptor = ArgumentCaptor.forClass(Chat.class);
        verify(chatRepository).save(chatCaptor.capture());
        assertEquals(USER_ID, chatCaptor.getValue().getUserId());
    }

    @Test
    void claimsLegacyChatsForAuthenticatedUser() {
        ChatRepository chatRepository = mock(ChatRepository.class);
        MessageRepository messageRepository = mock(MessageRepository.class);
        ChatController controller = new ChatController(chatRepository, messageRepository, artifactService());
        Chat currentChat = new Chat("Atual", "");
        currentChat.setId(2L);
        currentChat.setUserId(USER_ID);
        Chat legacyChat = new Chat("Histórico antigo", "");
        legacyChat.setId(1L);
        when(chatRepository.findByUserIdOrderByUpdatedAtDesc(USER_ID)).thenReturn(List.of(currentChat));
        when(chatRepository.findByUserIdIsNullOrderByUpdatedAtDesc()).thenReturn(List.of(legacyChat));

        List<ChatResponse> chats =
                controller.getAllChats(principal(USER_ID)).getBody().getData();

        assertEquals(2, chats.size());
        assertEquals(USER_ID, legacyChat.getUserId());
        verify(chatRepository).saveAll(List.of(legacyChat));
    }

    @Test
    void rejectsChatAccessFromAnotherOwner() {
        ChatRepository chatRepository = mock(ChatRepository.class);
        MessageRepository messageRepository = mock(MessageRepository.class);
        ChatController controller = new ChatController(chatRepository, messageRepository, artifactService());
        when(chatRepository.findByIdAndUserId(7L, OTHER_USER_ID)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> controller.getMessages(7L, principal(OTHER_USER_ID)));
    }

    @Test
    void permanentlyDeletesChatMessagesArtifactsAndChat() throws Exception {
        ChatRepository chatRepository = mock(ChatRepository.class);
        MessageRepository messageRepository = mock(MessageRepository.class);
        ChatController controller = new ChatController(chatRepository, messageRepository, artifactService());
        Chat chat = new Chat("Apagar", "");
        chat.setId(7L);
        chat.setUserId(USER_ID);
        Message message = new Message(7L, "assistant", "Imagem: ![](/api/media/avento-image-test.png)");
        Path generatedImage = Files.writeString(mediaDirectory.resolve("avento-image-test.png"), "image");
        when(chatRepository.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(chat));
        when(messageRepository.findByChatIdOrderByTimestampAsc(7L)).thenReturn(List.of(message));

        ChatDeletionResult result =
                controller.deleteChat(7L, principal(USER_ID)).getBody().getData();

        assertEquals(1, result.deletedMessages());
        assertEquals(1, result.deletedArtifacts());
        assertFalse(Files.exists(generatedImage));
        verify(messageRepository).deleteAllInBatch(List.of(message));
        verify(chatRepository).delete(chat);
        verify(chatRepository).flush();
    }

    @Test
    void keepsDatabaseRecordsWhenAnArtifactCannotBeDeleted() throws Exception {
        ChatRepository chatRepository = mock(ChatRepository.class);
        MessageRepository messageRepository = mock(MessageRepository.class);
        ChatController controller = new ChatController(chatRepository, messageRepository, artifactService());
        Chat chat = new Chat("Apagar", "");
        chat.setId(7L);
        chat.setUserId(USER_ID);
        Message message = new Message(7L, "assistant", "![](/api/media/avento-image-blocked.png)");
        Path blockedArtifact = Files.createDirectory(mediaDirectory.resolve("avento-image-blocked.png"));
        Files.writeString(blockedArtifact.resolve("open.txt"), "open");
        when(chatRepository.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(chat));
        when(messageRepository.findByChatIdOrderByTimestampAsc(7L)).thenReturn(List.of(message));

        ResponseStatusException error =
                assertThrows(ResponseStatusException.class, () -> controller.deleteChat(7L, principal(USER_ID)));

        assertEquals(500, error.getStatusCode().value());
        verify(messageRepository, never()).deleteAllInBatch(any());
        verify(chatRepository, never()).delete(any(Chat.class));
    }

    private ChatArtifactService artifactService() {
        return new ChatArtifactService(mediaDirectory.toString());
    }

    private AuthPrincipal principal(UUID userId) {
        return new AuthPrincipal(
                userId,
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "access-jti",
                "user@example.com",
                "User",
                UserRole.ROOT);
    }
}
