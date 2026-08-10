package com.avento.service.agent;

import com.avento.dto.ToolCall;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.FluxSink;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * Executa as ferramentas solicitadas em uma rodada do agente.
 *
 * <p>O estado da run continua mutável e pertence ao fluxo do agente. Esta classe apenas separa a
 * execução de ferramentas da decisão de encerramento feita por {@link AgentService}.
 */
final class ToolInvocationLoop {

    private static final int REPEATED_TOOL_FAILURE_LIMIT = 2;

    private final AgentPermissionService permissionService;
    private final AgentTimelineService timelineService;
    private final Set<String> planApprovedRuns;
    private final int maxToolCalls;
    private final Operations operations;

    ToolInvocationLoop(
            AgentPermissionService permissionService,
            AgentTimelineService timelineService,
            Set<String> planApprovedRuns,
            int maxToolCalls,
            Operations operations) {
        this.permissionService = permissionService;
        this.timelineService = timelineService;
        this.planApprovedRuns = planApprovedRuns;
        this.maxToolCalls = maxToolCalls;
        this.operations = operations;
    }

    void execute(
            String model,
            ArrayNode messages,
            AgentService.AgentRunState state,
            int round,
            FluxSink<String> sink,
            List<ToolCall> toolCalls) {
        boolean mediaGenerationAttempted = false;
        boolean mediaGenerationCompleted = false;
        for (ToolCall toolCall : toolCalls) {
            toolCall = operations.enrich(toolCall, state);
            if (state.executedToolCalls >= maxToolCalls) {
                sink.next(operations.eventChunk(
                        "agent.limit.reached",
                        "Limite total de ferramentas atingido",
                        "O Avento parou antes de chamar novas ferramentas."));
                sink.next(operations.contentChunk("\n> Limite total de ferramentas atingido.\n"));
                break;
            }

            if (usesFilesystemRoot(toolCall)) {
                planApprovedRuns.remove(state.runId);
                sink.next(operations.eventChunk(
                        "tool.rejected", "Ferramenta rejeitada", toolCall.name() + " tentou usar path /."));
                sink.next(operations.contentChunk(
                        "\nNão vou usar `/` como caminho de ferramenta. Se você quiser que eu analise arquivos, selecione ou informe uma pasta de projeto autorizada.\n"));
                sink.complete();
                return;
            }

            if (operations.requiresApproval(toolCall.name())) {
                boolean planApproved = planApprovedRuns.contains(state.runId) && !operations.isAlwaysConfirm(toolCall);
                if (permissionService.canAutoApprove(
                                state.runId,
                                state.userId,
                                toolCall.name(),
                                operations.permissionArguments(toolCall),
                                state.workspaceRoots)
                        || planApproved) {
                    timelineService.record(
                            state.runId,
                            "tool.permission.auto_approved",
                            toolCall.name(),
                            planApproved
                                    ? "Plano ja aprovado nesta resposta."
                                    : "Permissao salva aplicada automaticamente.",
                            toolCall.arguments());
                    state.executedToolCalls++;
                    JsonNode toolResult = operations.executeToolCall(messages, sink, toolCall, state.runId, state.userId);
                    recordToolOutcome(state, toolCall, toolResult);
                    operations.emitGeneratedMediaCompletion(toolCall, toolResult, sink);
                    if (operations.isMediaGenerationTool(toolCall)) {
                        mediaGenerationAttempted = true;
                        operations.emitMediaGenerationFailure(toolCall, toolResult, sink);
                    }
                    mediaGenerationCompleted |= operations.isSuccessfulMediaGeneration(toolCall, toolResult);
                    continue;
                }
                operations.requestToolApproval(model, messages, state, round, toolCall, true, sink);
                sink.complete();
                return;
            }

            state.executedToolCalls++;
            JsonNode toolResult = operations.executeToolCall(messages, sink, toolCall, state.runId, state.userId);
            recordToolOutcome(state, toolCall, toolResult);
            operations.emitGeneratedMediaCompletion(toolCall, toolResult, sink);
            if (operations.isMediaGenerationTool(toolCall)) {
                mediaGenerationAttempted = true;
                operations.emitMediaGenerationFailure(toolCall, toolResult, sink);
            }
            mediaGenerationCompleted |= operations.isSuccessfulMediaGeneration(toolCall, toolResult);
        }

        if (mediaGenerationAttempted) {
            planApprovedRuns.remove(state.runId);
            sink.next(operations.eventChunk(
                    "agent.round.completed",
                    mediaGenerationCompleted ? "Mídia gerada" : "Geração de mídia encerrada",
                    mediaGenerationCompleted
                            ? "A geração foi concluída pela ferramenta; nenhuma resposta adicional do modelo foi necessária."
                            : "A ferramenta retornou um erro técnico; nenhuma explicação inventada pelo modelo foi adicionada."));
            sink.complete();
            return;
        }

        if (state.consecutiveToolFailures >= REPEATED_TOOL_FAILURE_LIMIT) {
            planApprovedRuns.remove(state.runId);
            String failedTool = state.lastFailedTool;
            sink.next(operations.eventChunk(
                    "agent.tool.repeated_failure",
                    "Ferramenta falhando repetidamente",
                    "A ferramenta " + failedTool + " falhou " + state.consecutiveToolFailures
                            + " vezes seguidas; parando para não insistir."));
            sink.next(operations.contentChunk("\n> A ferramenta `" + failedTool + "` falhou "
                    + state.consecutiveToolFailures
                    + " vezes seguidas — provavelmente está indisponível ou não conectada. Parei aqui em vez"
                    + " de insistir. Verifique essa ferramenta ou me peça por outro caminho.\n"));
            sink.complete();
            return;
        }

        if (state.consecutiveIdenticalToolCalls >= 2) {
            String toolName = state.lastToolCallSignature.contains(":")
                    ? state.lastToolCallSignature.substring(0, state.lastToolCallSignature.indexOf(':'))
                    : state.lastToolCallSignature;
            var nudge = messages.addObject();
            nudge.put("role", "user");
            nudge.put(
                    "content",
                    "[Aviso de Orientação do Avento] A ação `" + toolName
                            + "` já foi executada e confirmada com sucesso neste passo. Não repita esta mesma chamada nem o texto introdutório. Avance para a próxima ação necessária ou conclua a tarefa fornecendo a resposta dos resultados.");
        }

        operations.continueTurn(model, messages, state, round + 1, sink);
    }

    private boolean usesFilesystemRoot(ToolCall toolCall) {
        JsonNode path = toolCall.arguments().path("path");
        return path.isTextual() && "/".equals(path.asText().trim());
    }

    private void recordToolOutcome(AgentService.AgentRunState state, ToolCall toolCall, JsonNode toolResult) {
        String toolName = toolCall.name();
        boolean failed = toolResult != null && toolResult.has("error");
        if (!failed) {
            state.lastFailedTool = "";
            state.consecutiveToolFailures = 0;
        } else if (toolName.equals(state.lastFailedTool)) {
            state.consecutiveToolFailures++;
        } else {
            state.lastFailedTool = toolName;
            state.consecutiveToolFailures = 1;
        }

        String signature = toolName + ":" + (toolCall.arguments() != null ? toolCall.arguments().toString() : "");
        if (signature.equals(state.lastToolCallSignature)) {
            state.consecutiveIdenticalToolCalls++;
        } else {
            state.lastToolCallSignature = signature;
            state.consecutiveIdenticalToolCalls = 1;
        }
    }

    interface Operations {
        ToolCall enrich(ToolCall toolCall, AgentService.AgentRunState state);

        boolean requiresApproval(String toolName);

        boolean isAlwaysConfirm(ToolCall toolCall);

        JsonNode permissionArguments(ToolCall toolCall);

        JsonNode executeToolCall(
                ArrayNode messages, FluxSink<String> sink, ToolCall toolCall, String runId, UUID userId);

        void requestToolApproval(
                String model,
                ArrayNode messages,
                AgentService.AgentRunState state,
                int round,
                ToolCall toolCall,
                boolean continueAfterTool,
                FluxSink<String> sink);

        void emitGeneratedMediaCompletion(ToolCall toolCall, JsonNode toolResult, FluxSink<String> sink);

        boolean isMediaGenerationTool(ToolCall toolCall);

        void emitMediaGenerationFailure(ToolCall toolCall, JsonNode toolResult, FluxSink<String> sink);

        boolean isSuccessfulMediaGeneration(ToolCall toolCall, JsonNode toolResult);

        String eventChunk(String type, String title, String detail);

        String contentChunk(String content);

        void continueTurn(
                String model,
                ArrayNode messages,
                AgentService.AgentRunState state,
                int nextRound,
                FluxSink<String> sink);
    }
}
