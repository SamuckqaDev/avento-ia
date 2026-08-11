package com.avento.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.model.ChatRepository;
import com.avento.model.Message;
import com.avento.model.MessageRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class RunReplyPersistenceServiceTest {

    private static final Long CHAT = 12L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void persistsTheFinalVisibleReplyUsingTheRunAsIdempotencyKey() {
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.findByRunId("run_1")).thenReturn(Optional.empty());
        when(messages.saveAndFlush(any(Message.class))).thenAnswer(invocation -> {
            Message saved = invocation.getArgument(0);
            saved.setId(44L);
            return saved;
        });
        RunReplyPersistenceService service = new RunReplyPersistenceService(messages, mock(ChatRepository.class), MAPPER);

        service.observe("run_1", chunk("Relatório pronto."));

        assertThat(service.persistCompletedReply("run_1", CHAT)).isTrue();
        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messages).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo("assistant");
        assertThat(saved.getValue().getContent()).isEqualTo("Relatório pronto.");
        assertThat(saved.getValue().getRunId()).isEqualTo("run_1");
    }

    @Test
    void doesNotCreateAnotherMessageWhenTheRunAlreadyHasAReply() {
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.findByRunId("run_2")).thenReturn(Optional.of(new Message(CHAT, "assistant", "existente")));
        RunReplyPersistenceService service = new RunReplyPersistenceService(messages, null, MAPPER);

        service.observe("run_2", chunk("nova tentativa"));

        assertThat(service.persistCompletedReply("run_2", CHAT)).isTrue();
        verify(messages, never()).saveAndFlush(any());
    }

    @Test
    void ignoresLifecycleEventsInsteadOfSavingThemAsChatText() {
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.findByRunId("run_3")).thenReturn(Optional.empty());
        RunReplyPersistenceService service = new RunReplyPersistenceService(messages, null, MAPPER);

        service.observe("run_3", "{\"avento_event\":{\"type\":\"agent.run.completed\"}}");

        assertThat(service.persistCompletedReply("run_3", CHAT)).isFalse();
        verify(messages, never()).saveAndFlush(any());
    }

    private String chunk(String content) {
        return "{\"choices\":[{\"delta\":{\"content\":\"" + content + "\"}}]}";
    }
}
