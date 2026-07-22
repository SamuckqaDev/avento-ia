package com.avento.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.model.AgentProfile;
import com.avento.model.AgentTask;
import com.avento.repository.AgentProfileRepository;
import com.avento.service.AgentProfileService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentRoutingServiceTest {

    private final AgentProfileRepository repository = mock(AgentProfileRepository.class);
    private final AgentProfileService agentProfileService = mock(AgentProfileService.class);
    private final AgentRoutingService service = new AgentRoutingService(repository, agentProfileService);
    private final UUID userId = UUID.randomUUID();

    private AgentProfile agent(Long id, String name, String specialty, String triggers) {
        AgentProfile agent = new AgentProfile(userId, name, specialty, "instr", "", triggers, null, false);
        agent.setId(id);
        return agent;
    }

    private AgentTask task(String title, String details) {
        return new AgentTask(1L, userId, 1, title, details, "[]", AgentTask.STATUS_PENDING, false);
    }

    @Test
    void manualAssignmentWins() {
        AgentTask task = task("qualquer", "coisa");
        task.setAssignedAgentId(7L);
        when(repository.findByIdAndUserId(7L, userId)).thenReturn(Optional.of(agent(7L, "Manual", "", "")));

        assertThat(service.pick(userId, task).agent().getName()).isEqualTo("Manual");
    }

    @Test
    void autoMatchesByTriggers() {
        when(repository.findByUserIdOrderByUpdatedAtDesc(userId))
                .thenReturn(List.of(
                        agent(1L, "Backend", "APIs Java", "controller,endpoint,repository"),
                        agent(2L, "UI", "React", "componente,estilo,tela")));

        AgentRoutingService.Routed routed = service.pick(userId, task("Criar controller de pedidos", "novo endpoint"));

        assertThat(routed.agent().getName()).isEqualTo("Backend");
    }

    @Test
    void fallsBackToDefaultWhenNothingMatches() {
        when(repository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());
        when(agentProfileService.resolveDefault(userId)).thenReturn(agent(9L, "Generalista", "", ""));

        AgentRoutingService.Routed routed = service.pick(userId, task("algo genérico", "sem palavra-chave"));

        assertThat(routed.agent().getName()).isEqualTo("Generalista");
    }
}
