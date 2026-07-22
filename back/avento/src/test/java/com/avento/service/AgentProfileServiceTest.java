package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.model.AgentProfile;
import com.avento.repository.AgentProfileRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AgentProfileServiceTest {

    private final AgentProfileRepository repository = Mockito.mock(AgentProfileRepository.class);
    private final AgentProfileService service = new AgentProfileService(repository);
    private final UUID userId = UUID.randomUUID();

    @Test
    void creatingAsDefaultDemotesOtherDefaults() {
        AgentProfile existingDefault = agent(1L, "Antigo", true);
        when(repository.save(any())).thenAnswer(invocation -> {
            AgentProfile saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(2L);
            }
            return saved;
        });
        when(repository.findByUserIdOrderByUpdatedAtDesc(userId))
                .thenReturn(List.of(existingDefault, agent(2L, "Backend", true)));

        service.create(userId, "Backend", "Java", "instr", "", "java,spring", null, true);

        // O default antigo é rebaixado para não haver dois defaults.
        assertFalse(existingDefault.isDefault());
    }

    @Test
    void listCreatesGeneralistaWhenUserHasNoAgents() {
        when(repository.existsByUserId(userId)).thenReturn(false);
        when(repository.findFirstByUserIdAndIsDefaultTrue(userId)).thenReturn(Optional.empty());
        when(repository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.list(userId);

        ArgumentCaptor<AgentProfile> captor = ArgumentCaptor.forClass(AgentProfile.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Generalista");
        assertTrue(captor.getValue().isDefault());
    }

    @Test
    void cannotReadOrMutateAnotherUsersAgent() {
        UUID otherUser = UUID.randomUUID();
        when(repository.findByIdAndUserId(9L, otherUser)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.get(otherUser, 9L));
        verify(repository, never()).save(any());
    }

    @Test
    void nullUserIsRejected() {
        assertThrows(SecurityException.class, () -> service.list(null));
    }

    private AgentProfile agent(Long id, String name, boolean isDefault) {
        AgentProfile agent = new AgentProfile(userId, name, "spec", "instr", "", "", null, isDefault);
        agent.setId(id);
        return agent;
    }
}
