package com.avento.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.model.AgentPermissionRule;
import com.avento.repository.AgentPermissionRuleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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
}
