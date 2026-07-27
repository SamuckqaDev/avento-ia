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

    /**
     * A run que termina em falha de ferramenta completa o fluxo SEM erro e com status FAILED. O
     * evento terminal so era publicado quando o status era exatamente COMPLETED, entao nada saia — e
     * como o SSE so fecha em agent.run.completed|failed|cancelled, o navegador ficava girando para
     * sempre com o servidor ja parado. O log dizia "completed" porque aquela linha e incondicional,
     * o que escondia o problema de quem olhasse so o log.
     */
    @Test
    void alwaysPublishesATerminalEventWhenTheStreamEnds() {
        List<String> published = new java.util.ArrayList<>();
        AgentRunRegistry registry = new AgentRunRegistry(mapper);
        FailingToolEngine engine = new FailingToolEngine(registry);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                engine,
                registry,
                mapper,
                new com.avento.service.AgentTimelineService(java.util.Optional.empty()),
                (runId, userId, chatId, raw) -> published.add(raw));
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject().put("role", "user").put("content", "pesquisa isso pra mim");

        orchestrator.stream("qwen3.5:9b", messages, List.of(), "", ImageGenerationOptions.defaults(), 9L, null)
                .collectList()
                .block();

        assertTrue(
                published.stream().anyMatch(event -> event.contains("agent.run.failed")),
                "sem evento terminal o SSE nunca fecha e a interface trava: " + published);
    }

    /** Emite falha de ferramenta e encerra sem erro — o caminho que travava. */
    private static class FailingToolEngine implements AgentExecutionEngine {
        private final AgentRunRegistry registry;

        FailingToolEngine(AgentRunRegistry registry) {
            this.registry = registry;
        }

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
            return Flux.just("{\"avento_event\":{\"type\":\"agent.tool.repeated_failure\"}}")
                    .doOnNext(chunk -> registry.fail(runId));
        }

        @Override
        public Flux<String> approveTool(String approvalId, String comment) {
            return Flux.empty();
        }

        @Override
        public Flux<String> rejectTool(String approvalId, String comment) {
            return Flux.empty();
        }
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
