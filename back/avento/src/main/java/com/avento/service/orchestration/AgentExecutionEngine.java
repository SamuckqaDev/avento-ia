package com.avento.service.orchestration;

import com.avento.service.image.ImageGenerationOptions;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Flux;

/** Model-turn contract consumed by the orchestration lifecycle. */
public interface AgentExecutionEngine {

    Flux<String> streamChat(
            String model,
            ArrayNode messages,
            List<String> workspaceRoots,
            String imageModel,
            ImageGenerationOptions imageOptions,
            String runId,
            Long chatId,
            UUID userId);

    Flux<String> approveTool(String approvalId, String comment);

    Flux<String> rejectTool(String approvalId, String comment);
}
