package com.avento.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.model.AgentPermissionRule;
import com.avento.repository.AgentPermissionRuleRepository;
import com.avento.service.tools.RunToolPolicyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AgentPermissionServiceTest {

    @Test
    void doesNotReuseRememberedPermissionForAnotherUser() {
        AgentPermissionRuleRepository repository = mock(AgentPermissionRuleRepository.class);
        UUID owner = UUID.randomUUID();
        AgentPermissionRule rule =
                new AgentPermissionRule(owner, "/project", "open_app", "open_app:terminal", "ALLOW", null);
        when(repository.findByUserIdAndToolNameAndResourceKeyAndProjectPathOrderByCreatedAtDesc(
                        any(), any(), any(), any()))
                .thenAnswer(invocation -> owner.equals(invocation.getArgument(0)) ? List.of(rule) : List.of());
        AgentPermissionService service = new AgentPermissionService(Optional.of(repository));
        ObjectNode arguments = new ObjectMapper().createObjectNode().put("appName", "Terminal");

        assertTrue(service.canAutoApprove(owner, "open_app", arguments, List.of("/project")));
        assertFalse(service.canAutoApprove(UUID.randomUUID(), "open_app", arguments, List.of("/project")));
    }

    /**
     * A aprovação serve dois públicos opostos com o mesmo código. Tarefa agendada roda sem ninguém
     * para responder: perguntar ali trava até o timeout de inatividade, de madrugada, em silêncio.
     * Conversa interativa é o contrário — não perguntar é agir sem consentimento.
     *
     * <p>O bug que estes testes prendem: o fallback da preferência era {@code true}. Como o Redis de
     * desenvolvimento não persiste, todo restart apagava a chave, tudo voltava a ser auto-aprovado e
     * o botão do header não tinha efeito — aprovação que não pede é pior que aprovação nenhuma,
     * porque cria confiança falsa.
     */
    @Test
    void autonomousRunApprovesWithoutAskingAnyone() {
        RunToolPolicyRegistry registry = new RunToolPolicyRegistry();
        registry.markAutonomous("run_cowork");

        assertTrue(canDeleteFile(serviceWith(preferenceDisabled(), registry), "run_cowork"));
    }

    // O caso que quebrava: sem preferencia gravada, PERGUNTAR — nunca aprovar por omissao.
    @Test
    void interactiveRunAsksWhenThePreferenceWasNeverStored() {
        assertFalse(canDeleteFile(serviceWith(preferenceDisabled(), new RunToolPolicyRegistry()), "run_chat"));
    }

    @Test
    void interactiveRunHonoursThePreferenceWhenTheUserEnabledIt() {
        UserSettingsService settings = mock(UserSettingsService.class);
        when(settings.autoApproveAllEnabled(any(), any(Boolean.class))).thenReturn(true);

        assertTrue(canDeleteFile(serviceWith(settings, new RunToolPolicyRegistry()), "run_chat"));
    }

    // A marca e por run: uma tarefa autonoma nao pode vazar aprovacao para a conversa ao lado.
    @Test
    void autonomyDoesNotLeakToOtherRuns() {
        RunToolPolicyRegistry registry = new RunToolPolicyRegistry();
        registry.markAutonomous("run_cowork");
        AgentPermissionService service = serviceWith(preferenceDisabled(), registry);

        assertFalse(canDeleteFile(service, "run_chat"));
        assertFalse(canDeleteFile(service, null));
    }

    @Test
    void clearingTheRunDropsItsAutonomy() {
        RunToolPolicyRegistry registry = new RunToolPolicyRegistry();
        registry.markAutonomous("run_cowork");
        registry.clear("run_cowork");

        assertFalse(canDeleteFile(serviceWith(preferenceDisabled(), registry), "run_cowork"));
    }

    private UserSettingsService preferenceDisabled() {
        UserSettingsService settings = mock(UserSettingsService.class);
        when(settings.autoApproveAllEnabled(any(), any(Boolean.class))).thenReturn(false);
        return settings;
    }

    @SuppressWarnings("unchecked")
    private AgentPermissionService serviceWith(UserSettingsService settings, RunToolPolicyRegistry registry) {
        ObjectProvider<UserSettingsService> settingsProvider = mock(ObjectProvider.class);
        when(settingsProvider.getIfAvailable()).thenReturn(settings);
        ObjectProvider<RunToolPolicyRegistry> registryProvider = mock(ObjectProvider.class);
        when(registryProvider.getIfAvailable()).thenReturn(registry);
        return new AgentPermissionService(
                Optional.of(mock(AgentPermissionRuleRepository.class)), settingsProvider, registryProvider);
    }

    private boolean canDeleteFile(AgentPermissionService service, String runId) {
        return service.canAutoApprove(
                runId, UUID.randomUUID(), "delete_file", new ObjectMapper().createObjectNode(), List.of());
    }
}
