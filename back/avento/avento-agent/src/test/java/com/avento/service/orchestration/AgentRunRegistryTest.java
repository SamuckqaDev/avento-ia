package com.avento.service.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.service.dto.AgentRunSnapshot;
import com.avento.service.orchestration.AgentRunRegistry.AgentRunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentRunRegistryTest {

    private final AgentRunRegistry registry = new AgentRunRegistry(new ObjectMapper());

    @Test
    void tracksApprovalAndCompletesOnlyAfterExecutionResumes() {
        registry.start("run_1", "Crie um projeto NestJS", List.of("/tmp/workspace"));
        registry.observe(
                "run_1", "{\"avento_event\":{\"type\":\"tool.approval.required\",\"approvalId\":\"approval_1\"}}");

        assertEquals(
                AgentRunStatus.AWAITING_APPROVAL,
                registry.find("run_1").orElseThrow().status());
        assertEquals("run_1", registry.findRunIdByApproval("approval_1").orElseThrow());

        registry.finish("run_1");
        assertEquals(
                AgentRunStatus.AWAITING_APPROVAL,
                registry.find("run_1").orElseThrow().status());

        registry.resumeApproval("approval_1");
        registry.finish("run_1");

        assertEquals(
                AgentRunStatus.COMPLETED, registry.find("run_1").orElseThrow().status());
    }

    @Test
    void recordsFailedToolEvents() {
        registry.start("run_2", "Execute os testes", List.of());

        registry.observe("run_2", "{\"avento_event\":{\"type\":\"tool.failed\"}}");
        registry.observe("run_2", "{\"avento_event\":{\"type\":\"agent.round.completed\"}}");
        registry.finish("run_2");

        assertEquals(AgentRunStatus.FAILED, registry.find("run_2").orElseThrow().status());
    }

    @Test
    void marksRejectedApprovalsAsCancelled() {
        registry.start("run_3", "Apague a pasta", List.of());
        registry.observe(
                "run_3", "{\"avento_event\":{\"type\":\"tool.approval.required\",\"approvalId\":\"approval_3\"}}");

        registry.observe("run_3", "{\"avento_event\":{\"type\":\"tool.rejected\"}}");
        registry.finish("run_3");

        assertEquals(
                AgentRunStatus.CANCELLED, registry.find("run_3").orElseThrow().status());
    }

    @Test
    void onlyReturnsRunsOwnedByTheUser() {
        UUID firstUser = UUID.randomUUID();
        UUID secondUser = UUID.randomUUID();
        registry.start("run_first", "First", List.of(), firstUser);
        registry.start("run_second", "Second", List.of(), secondUser);

        assertEquals(
                List.of("run_first"),
                registry.recent(firstUser).stream().map(AgentRunSnapshot::runId).toList());
        assertEquals(0, registry.recent(UUID.randomUUID()).size());
        assertTrue(registry.findOwned("run_first", secondUser).isEmpty());
    }

    @Test
    void updatesRunActivityWhenTheModelStreamsContent() {
        AgentRunSnapshot started = registry.start("run_stream", "Analyze", List.of());

        registry.observe("run_stream", "{\"choices\":[{\"delta\":{\"content\":\"working\"}}]}");

        AgentRunSnapshot updated = registry.find("run_stream").orElseThrow();
        assertEquals(started.status(), updated.status());
        assertEquals(started.lastEventType(), updated.lastEventType());
        assertTrue(!updated.updatedAt().isBefore(started.updatedAt()));
        assertTrue(updated.updatedAt().isAfter(LocalDateTime.MIN));
    }
}
