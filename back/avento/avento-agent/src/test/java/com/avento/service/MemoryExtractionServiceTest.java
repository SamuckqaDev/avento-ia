package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.model.Chat;
import com.avento.model.Message;
import com.avento.repository.ChatRepository;
import com.avento.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MemoryExtractionServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final ChatRepository chatRepository = mock(ChatRepository.class);
    private final UserMemoryService userMemoryService = mock(UserMemoryService.class);
    private final UUID userId = UUID.randomUUID();

    private MemoryExtractionService serviceReturning(String modelOutput, AtomicReference<String> promptSeen) {
        return new MemoryExtractionService(
                mapper,
                chatRepository,
                messageRepository,
                userMemoryService,
                "http://localhost:9",
                "qwen3:8b",
                "30m",
                true,
                60,
                4,
                0,
                12,
                4,
                8192) {
            @Override
            protected String requestExtraction(String conversation) {
                promptSeen.set(conversation);
                return modelOutput;
            }
        };
    }

    @Test
    void parsesModelLinesIntoSuggestionsDroppingBulletsAndCount() {
        String output =
                "- Prefere PT-BR informal\n2) Projeto se chama monicare\n* Usa styled-components\nExtra 1\nExtra 2";
        MemoryExtractionService service = serviceReturning(output, new AtomicReference<>());

        // maxSuggestions=4, então corta em 4 e limpa marcadores (-, 2), *).
        assertThat(service.parseFacts(output))
                .containsExactly(
                        "Prefere PT-BR informal", "Projeto se chama monicare", "Usa styled-components", "Extra 1");
    }

    @Test
    void treatsSentinelAsNoFacts() {
        MemoryExtractionService service = serviceReturning("NOTHING", new AtomicReference<>());
        assertThat(service.parseFacts("NOTHING")).isEmpty();
        assertThat(service.parseFacts("  nothing  ")).isEmpty();
        assertThat(service.parseFacts("NADA")).isEmpty();
    }

    @Test
    void extractsFromHistoryAndPushesSuggestions() throws Exception {
        when(chatRepository.findByIdAndUserId(1L, userId)).thenReturn(Optional.of(new Chat()));
        when(messageRepository.findByChatIdOrderByTimestampAsc(1L))
                .thenReturn(List.of(
                        new Message(1L, "user", "quero um app com styled-components"),
                        new Message(1L, "assistant", "beleza"),
                        new Message(1L, "user", "e me responde em PT-BR informal"),
                        new Message(1L, "assistant", "combinado")));
        when(userMemoryService.suggest(eq(userId), any(), any()))
                .thenReturn(new UserMemoryService.SuggestionOutcome(true, "x"));
        AtomicReference<String> prompt = new AtomicReference<>();
        MemoryExtractionService service = serviceReturning("- Prefere PT-BR informal\n- Usa styled-components", prompt);

        service.extract(userId, 1L);

        verify(userMemoryService, times(2)).suggest(eq(userId), any(), any());
        assertThat(prompt.get()).contains("styled-components").contains("PT-BR");
    }

    @Test
    void skipsWhenTooFewMessages() throws Exception {
        when(chatRepository.findByIdAndUserId(9L, userId)).thenReturn(Optional.of(new Chat()));
        when(messageRepository.findByChatIdOrderByTimestampAsc(9L)).thenReturn(List.of(new Message(9L, "user", "oi")));
        MemoryExtractionService service = serviceReturning("- alguma coisa", new AtomicReference<>());

        service.extract(userId, 9L);

        // 1 mensagem (< min 4): não deve sugerir nada.
        verify(userMemoryService, never()).suggest(any(), any(), any());
    }

    @Test
    void doesNotReadMessagesFromAChatOwnedByAnotherUser() throws Exception {
        when(chatRepository.findByIdAndUserId(77L, userId)).thenReturn(Optional.empty());
        MemoryExtractionService service = serviceReturning("- fact", new AtomicReference<>());

        service.extract(userId, 77L);

        verify(messageRepository, never()).findByChatIdOrderByTimestampAsc(77L);
        verify(userMemoryService, never()).suggest(any(), any(), any());
    }
}
