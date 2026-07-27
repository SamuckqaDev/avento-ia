package com.avento.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.model.AgentTimelineEvent;
import com.avento.repository.AgentTimelineEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentTimelineServiceTest {

    @Test
    void associatesOwnerAndRedactsSensitivePayloads() {
        AgentTimelineEventRepository repository = mock(AgentTimelineEventRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AgentTimelineService service = new AgentTimelineService(Optional.of(repository));
        UUID userId = UUID.randomUUID();
        service.registerRun("run_1", userId, 42L);
        ObjectNode payload = new ObjectMapper().createObjectNode();
        payload.put("token", "private-token");
        payload.put("content", "x".repeat(500));
        payload.put("preview", "data:image/png;base64,AAAA");

        service.record("run_1", "tool.completed", "read_file", "token=private-token", payload);

        ArgumentCaptor<AgentTimelineEvent> captor = ArgumentCaptor.forClass(AgentTimelineEvent.class);
        verify(repository).save(captor.capture());
        AgentTimelineEvent event = captor.getValue();
        assertEquals(userId, event.getUserId());
        assertEquals(42L, event.getChatId());
        assertTrue(event.getPayload().contains("[redacted]"));
        assertTrue(event.getPayload().contains("[omitted: 500 chars]"));
        assertTrue(event.getPayload().contains("[binary data omitted]"));
        assertFalse(event.getPayload().contains("private-token"));
        assertFalse(event.getDetail().contains("private-token"));
    }
}
