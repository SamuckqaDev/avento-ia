package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.avento.api.dto.UsageSummary;
import com.avento.model.TokenUsage;
import com.avento.repository.TokenUsageRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class TokenUsageServiceTest {

    @Mock
    private TokenUsageRepository repository;

    private TokenUsageService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new TokenUsageService(repository);
    }

    @Test
    void testRecordValidUsage() {
        UUID userId = UUID.randomUUID();
        Long chatId = 1L;
        String runId = "run-1";
        String model = "qwen2.5:7b";
        int prompt = 100;
        int completion = 50;

        service.record(userId, chatId, runId, model, prompt, completion);

        ArgumentCaptor<TokenUsage> captor = ArgumentCaptor.forClass(TokenUsage.class);
        verify(repository).save(captor.capture());
        TokenUsage saved = captor.getValue();

        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getChatId()).isEqualTo(chatId);
        assertThat(saved.getRunId()).isEqualTo(runId);
        assertThat(saved.getModel()).isEqualTo(model);
        assertThat(saved.getPromptTokens()).isEqualTo(prompt);
        assertThat(saved.getCompletionTokens()).isEqualTo(completion);
        assertThat(saved.getTotalTokens()).isEqualTo(150);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void testSummary() {
        UUID userId = UUID.randomUUID();
        when(repository.sumTotalSince(eq(userId), any())).thenReturn(100L);
        when(repository.sumByModelSince(eq(userId), any())).thenReturn(List.of());
        when(repository.sumByDaySince(eq(userId), any())).thenReturn(List.of());

        UsageSummary summary = service.summary(userId, "today");

        assertThat(summary.total()).isEqualTo(100L);
        verify(repository).sumTotalSince(eq(userId), any());
    }
}
