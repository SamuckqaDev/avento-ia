package com.avento.service.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.service.image.ImageGenerationOptions;
import com.avento.service.orchestration.AgentRunRegistry.AgentRunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class AgentOrchestratorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void keepsTheSameRunAcrossApprovalAndResumedExecution() {
        FakeAgentExecutionEngine agentService = new FakeAgentExecutionEngine();
        AgentRunRegistry registry = new AgentRunRegistry(mapper);
        AgentOrchestrator orchestrator = new AgentOrchestrator(agentService, registry, mapper);
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject().put("role", "user").put("content", "Crie um projeto NestJS em back");
        List<String> firstStream = orchestrator.stream(
                        "qwen3:8b", messages, List.of("/workspace"), "", ImageGenerationOptions.defaults(), 7L, null)
                .collectList()
                .block();

        assertTrue(firstStream.get(0).contains(agentService.runId));
        assertEquals(
                AgentRunStatus.AWAITING_APPROVAL,
                registry.find(agentService.runId).orElseThrow().status());

        orchestrator.approve("approval_7", "pode executar").collectList().block();

        assertEquals(
                AgentRunStatus.COMPLETED,
                registry.find(agentService.runId).orElseThrow().status());
    }

    private static class FakeAgentExecutionEngine implements AgentExecutionEngine {
        private String runId;

        @Override
        public Flux<String> streamChat(
                String model,
                ArrayNode messages,
                List<String> workspaceRoots,
                String imageModel,
                ImageGenerationOptions imageOptions,
                String runId,
                Long chatId,
                UUID userId) {
            this.runId = runId;
            return Flux.just("{\"avento_event\":{\"type\":\"tool.approval.required\",\"approvalId\":\"approval_7\"}}");
        }

        @Override
        public Flux<String> approveTool(String approvalId, String comment) {
            return Flux.just(
                    "{\"avento_event\":{\"type\":\"tool.approval.accepted\"}}",
                    "{\"avento_event\":{\"type\":\"agent.round.completed\"}}");
        }

        @Override
        public Flux<String> rejectTool(String approvalId, String comment) {
            return Flux.empty();
        }
    }
}
