package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.model.UserMemory;
import com.avento.repository.UserMemoryRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UserMemoryServiceTest {

    private final UserMemoryRepository repository = mock(UserMemoryRepository.class);
    private final UserMemoryService service = new UserMemoryService(repository);
    private final UUID userId = UUID.randomUUID();

    @Test
    void manualAdditionsEnterActiveAndNormalizeWhitespace() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserMemory memory = service.addManual(userId, "  Prefere   styled-components ", "preferencia");

        assertEquals(UserMemory.STATUS_ACTIVE, memory.getStatus());
        assertEquals(UserMemory.ORIGIN_MANUAL, memory.getOrigin());
        assertEquals("Prefere styled-components", memory.getContent());
    }

    @Test
    void modelSuggestionsEnterPendingSoTheUserCanConfirm() {
        when(repository.existsByUserIdAndStatusAndContentIgnoreCase(any(), any(), any()))
                .thenReturn(false);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserMemoryService.SuggestionOutcome outcome = service.suggest(userId, "Chama o projeto de monicare", "projeto");

        assertTrue(outcome.saved());
        ArgumentCaptor<UserMemory> captor = ArgumentCaptor.forClass(UserMemory.class);
        verify(repository).save(captor.capture());
        assertEquals(UserMemory.STATUS_PENDING, captor.getValue().getStatus());
        assertEquals(UserMemory.ORIGIN_SUGGESTED, captor.getValue().getOrigin());
    }

    @Test
    void suggestionsThatDuplicateAKnownFactAreIgnored() {
        when(repository.existsByUserIdAndStatusAndContentIgnoreCase(eq(userId), eq(UserMemory.STATUS_ACTIVE), any()))
                .thenReturn(true);

        UserMemoryService.SuggestionOutcome outcome = service.suggest(userId, "Prefere PT-BR informal", "fato");

        assertFalse(outcome.saved());
        verify(repository, never()).save(any());
    }

    @Test
    void suggestionsThatDifferOnlyByAccentsAndPunctuationAreIgnored() {
        when(repository.findByUserIdOrderByUpdatedAtDesc(userId))
                .thenReturn(List.of(new UserMemory(
                        userId,
                        "Prefere respostas em português!",
                        "fato",
                        null,
                        UserMemory.STATUS_ACTIVE,
                        UserMemory.ORIGIN_MANUAL)));

        UserMemoryService.SuggestionOutcome outcome = service.suggest(userId, "prefere respostas em portugues", "fato");

        assertFalse(outcome.saved());
        verify(repository, never()).save(any());
    }

    @Test
    void promptBlockListsOnlyActiveFactsUnderAHeader() {
        when(repository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, UserMemory.STATUS_ACTIVE))
                .thenReturn(List.of(
                        new UserMemory(
                                userId,
                                "Prefere TypeScript",
                                "preferencia",
                                null,
                                UserMemory.STATUS_ACTIVE,
                                UserMemory.ORIGIN_MANUAL),
                        new UserMemory(
                                userId,
                                "Projeto se chama monicare",
                                "projeto",
                                null,
                                UserMemory.STATUS_ACTIVE,
                                UserMemory.ORIGIN_MANUAL)));

        String block = service.promptBlock(userId);

        assertThat(block)
                .contains("Memória do usuário")
                .contains("Prefere TypeScript")
                .contains("monicare");
    }

    @Test
    void promptBlockIsEmptyWithoutActiveMemories() {
        when(repository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, UserMemory.STATUS_ACTIVE))
                .thenReturn(List.of());

        assertEquals("", service.promptBlock(userId));
    }

    @Test
    void cannotReadOrMutateAnotherUsersMemory() {
        UUID otherUser = UUID.randomUUID();
        when(repository.findByIdAndUserId(7L, otherUser)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> service.confirm(otherUser, 7L));
        verify(repository, never()).save(any());
    }
}
