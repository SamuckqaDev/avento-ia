package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.*;
import com.avento.api.dto.BaseResponse;
import com.avento.api.dto.OperationResponse;
import com.avento.auth.security.AuthPrincipal;
import com.avento.model.AgentRunJob;
import com.avento.repository.ChatRepository;
import com.avento.service.AgentService;
import com.avento.service.ComfyUiImageService;
import com.avento.service.WorkspaceAccessService;
import com.avento.service.context.ConversationContextCache;
import com.avento.service.dto.AgentRunSnapshot;
import com.avento.service.dto.AgentRunView;
import com.avento.service.dto.LocalModelInfo;
import com.avento.service.execution.AgentRunSubmissionService;
import com.avento.service.execution.RunEventStreamService;
import com.avento.service.image.ImageGenerationOptions;
import com.avento.service.orchestration.AgentOrchestrator;
import com.avento.service.orchestration.AgentRunRegistry.AgentRunStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/ai")
public class LocalAiOrchestratorController {

    private final AgentService agentService;
    private final AgentOrchestrator agentOrchestrator;
    private final ComfyUiImageService comfyUiImageService;
    private final ObjectMapper mapper;
    private final WorkspaceAccessService workspaceAccessService;
    private final ChatRepository chatRepository;
    private final ConversationContextCache conversationContextCache;
    private final RunEventStreamService runEventStreamService;
    private final AgentRunSubmissionService runSubmissionService;

    public LocalAiOrchestratorController(
            AgentService agentService,
            AgentOrchestrator agentOrchestrator,
            ComfyUiImageService comfyUiImageService,
            ObjectMapper mapper,
            WorkspaceAccessService workspaceAccessService,
            ChatRepository chatRepository,
            ConversationContextCache conversationContextCache,
            RunEventStreamService runEventStreamService,
            AgentRunSubmissionService runSubmissionService) {
        this.agentService = agentService;
        this.agentOrchestrator = agentOrchestrator;
        this.comfyUiImageService = comfyUiImageService;
        this.mapper = mapper;
        this.workspaceAccessService = workspaceAccessService;
        this.chatRepository = chatRepository;
        this.conversationContextCache = conversationContextCache;
        this.runEventStreamService = runEventStreamService;
        this.runSubmissionService = runSubmissionService;
    }

    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<BaseResponse<List<String>>>> getModels() {
        return agentService.getModels().map(ApiResponses::ok);
    }

    @GetMapping(value = "/models/details", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<BaseResponse<List<LocalModelInfo>>>> getModelDetails() {
        return agentService.getModelDetails().map(ApiResponses::ok);
    }

    @GetMapping(value = "/models/images", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<BaseResponse<List<LocalModelInfo>>>> getImageModelDetails() {
        return Mono.zip(agentService.getImageModelDetails(), comfyUiImageService.getModels())
                .map(models -> {
                    if (comfyUiImageService.isComfyOnly()) {
                        return models.getT2();
                    }
                    List<LocalModelInfo> combined = new ArrayList<>(models.getT2());
                    combined.addAll(models.getT1());
                    return combined;
                })
                .map(ApiResponses::ok);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(
            @RequestBody JsonNode requestBody, @AuthenticationPrincipal AuthPrincipal principal) {
        UUID userId = principal == null ? null : principal.userId();
        List<String> workspaceRoots = registerWorkspaceRoots(userId, requestBody.path("workspaceRoots"));

        // Modelo em branco é resolvido pelo AgentService.normalizeChatModel usando o default
        // configurável (avento.agent.default-model) — nada de nome de modelo fixo em código.
        String model = requestBody.has("model") ? requestBody.get("model").asText() : "";
        String imageModel = requestBody.path("imageModel").asText("");
        ImageGenerationOptions imageOptions = ImageGenerationOptions.from(requestBody.path("imageOptions"));
        ArrayNode messages =
                requestBody.has("messages") && requestBody.get("messages").isArray()
                        ? (ArrayNode) requestBody.get("messages")
                        : mapper.createArrayNode();
        Long chatId = requestBody.path("chatId").canConvertToLong()
                ? requestBody.path("chatId").asLong()
                : null;
        if (chatId != null
                && userId != null
                && chatRepository.findByIdAndUserId(chatId, userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found");
        }

        messages = conversationContextCache.resolve(userId, chatId, messages);

        return agentOrchestrator.stream(model, messages, workspaceRoots, imageModel, imageOptions, chatId, userId);
    }

    @PostMapping(value = "/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BaseResponse<RunSubmissionResponse>> submitRun(
            @RequestBody JsonNode requestBody, @AuthenticationPrincipal AuthPrincipal principal) {
        UUID userId = userId(principal);
        Long chatId = requestBody.path("chatId").canConvertToLong()
                ? requestBody.path("chatId").asLong()
                : null;
        if (userId == null
                || chatId == null
                || chatRepository.findByIdAndUserId(chatId, userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found");
        }

        List<String> workspaceRoots = registerWorkspaceRoots(userId, requestBody.path("workspaceRoots"));
        ObjectNode sanitizedRequest =
                requestBody.isObject() ? ((ObjectNode) requestBody).deepCopy() : mapper.createObjectNode();
        sanitizedRequest.set("workspaceRoots", mapper.valueToTree(workspaceRoots));
        AgentRunJob job = runSubmissionService.submit(userId, chatId, sanitizedRequest);
        return ApiResponses.accepted(
                new RunSubmissionResponse(job.getRunId(), job.getStatus().name()));
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamRunEvents(
            @PathVariable String runId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        UUID userId = userId(principal);
        if (runSubmissionService.findOwned(runId, userId).isEmpty()
                && agentOrchestrator.registry().findOwned(runId, userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found");
        }
        return runEventStreamService.stream(userId, runId, lastEventId);
    }

    @PostMapping(value = "/runs/{runId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BaseResponse<OperationResponse>> cancelRun(
            @PathVariable String runId, @AuthenticationPrincipal AuthPrincipal principal) {
        if (!runSubmissionService.requestCancellation(runId, userId(principal))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found");
        }
        return ApiResponses.accepted(new OperationResponse("Cancelamento solicitado."));
    }

    @PostMapping(value = "/approvals/{approvalId}/approve", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> approveTool(
            @PathVariable String approvalId,
            @RequestBody(required = false) JsonNode requestBody,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireOwnedApproval(approvalId, principal);
        String runId = agentOrchestrator.runIdForApproval(approvalId).orElse("");
        UUID userId = userId(principal);
        return agentOrchestrator
                .approve(approvalId, approvalComment(requestBody))
                .doOnComplete(() -> runSubmissionService.finishAfterApproval(
                        runId,
                        userId,
                        agentOrchestrator
                                .registry()
                                .find(runId)
                                .map(snapshot -> snapshot.status() == AgentRunStatus.AWAITING_APPROVAL)
                                .orElse(false)))
                .doOnError(error -> runSubmissionService.failAfterApproval(runId, userId, error));
    }

    @PostMapping(value = "/approvals/{approvalId}/reject", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> rejectTool(
            @PathVariable String approvalId,
            @RequestBody(required = false) JsonNode requestBody,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireOwnedApproval(approvalId, principal);
        String runId = agentOrchestrator.runIdForApproval(approvalId).orElse("");
        UUID userId = userId(principal);
        return agentOrchestrator
                .reject(approvalId, approvalComment(requestBody))
                .doOnComplete(() -> runSubmissionService.cancelAfterRejection(runId, userId));
    }

    @GetMapping(value = "/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BaseResponse<List<AgentRunSnapshot>>> getRecentRuns(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponses.ok(agentOrchestrator.registry().recent(userId(principal)));
    }

    @GetMapping(value = "/runs/active/{chatId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BaseResponse<AgentRunView>> getActiveRun(
            @PathVariable Long chatId, @AuthenticationPrincipal AuthPrincipal principal) {
        UUID userId = userId(principal);
        if (userId == null || chatRepository.findByIdAndUserId(chatId, userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found");
        }
        return runSubmissionService
                .findActiveForChat(chatId, userId)
                .map(ApiResponses::ok)
                .orElseGet(() -> ApiResponses.ok(null));
    }

    @GetMapping(value = "/runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BaseResponse<AgentRunSnapshot>> getRun(
            @PathVariable String runId, @AuthenticationPrincipal AuthPrincipal principal) {
        return agentOrchestrator
                .registry()
                .findOwned(runId, userId(principal))
                .map(ApiResponses::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    }

    private String approvalComment(JsonNode requestBody) {
        if (requestBody == null || requestBody.isMissingNode() || requestBody.isNull()) {
            return "";
        }
        return requestBody.path("comment").asText("");
    }

    private void requireOwnedApproval(String approvalId, AuthPrincipal principal) {
        if (!agentService.approvalOwnedBy(approvalId, userId(principal))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval not found");
        }
    }

    private UUID userId(AuthPrincipal principal) {
        return principal == null ? null : principal.userId();
    }

    // Also returns the roots that were actually registered, so streamChat can tell the model
    // what they are — otherwise the model has no way to know the real paths and has to guess.
    private List<String> registerWorkspaceRoots(UUID userId, JsonNode workspaceRoots) {
        List<String> registered = new ArrayList<>();
        if (!workspaceRoots.isArray()) {
            return registered;
        }

        for (JsonNode root : workspaceRoots) {
            if (root.isTextual() && !root.asText().isBlank()) {
                try {
                    workspaceAccessService.registerWorkspaceRoot(userId, root.asText());
                    registered.add(root.asText());
                } catch (IllegalArgumentException ignored) {
                    // Stale or deleted workspace roots should not break the chat stream.
                }
            }
        }
        return registered;
    }
}
