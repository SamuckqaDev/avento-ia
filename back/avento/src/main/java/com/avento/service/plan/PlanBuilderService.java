package com.avento.service.plan;

import com.avento.api.dto.PlanCreateRequest;
import com.avento.model.AgentPlan;
import com.avento.model.AgentTask;
import com.avento.repository.AgentPlanRepository;
import com.avento.repository.AgentTaskRepository;
import com.avento.repository.ChatRepository;
import com.avento.service.AgentService;
import com.avento.service.WorkspaceAccessService;
import com.avento.service.plan.dto.AgentTaskDefinition;
import com.avento.service.plan.dto.PlanDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlanBuilderService {

    private static final int MAX_TASKS = 20;
    private static final int MAX_TITLE_CHARS = 160;
    private static final int MAX_DETAILS_CHARS = 4000;
    private static final String PLAN_BUILDER_PROMPT =
            com.avento.service.support.PromptResources.load("agent/prompts/plan-builder.md");

    private final AgentPlanRepository planRepository;
    private final AgentTaskRepository taskRepository;
    private final ChatRepository chatRepository;
    private final AgentService agentService;
    private final WorkspaceAccessService workspaceAccessService;
    private final ObjectMapper objectMapper;
    private final AgentRoutingService agentRoutingService;

    @Transactional
    public AgentPlan buildPlan(UUID userId, PlanCreateRequest request) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID is required");
        }
        if (chatRepository.findByIdAndUserId(request.chatId(), userId).isEmpty()) {
            throw new IllegalArgumentException("Chat not found or not owned by user");
        }
        List<String> workspaceRoots = request.workspaceRoots().stream()
                .map(root ->
                        workspaceAccessService.requireAuthorized(userId, root).toString())
                .distinct()
                .toList();

        AgentPlan plan = new AgentPlan(
                userId, request.chatId(), request.goal(), AgentPlan.STATUS_DRAFT, serializeRoots(workspaceRoots));
        plan = planRepository.save(plan);

        try {
            ArrayNode messages = objectMapper.createArrayNode();

            // System prompt
            ObjectNode sysMsg = messages.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", PLAN_BUILDER_PROMPT);

            // User prompt
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", "Goal: " + request.goal() + "\nWorkspace roots: " + workspaceRoots);

            String rawJson = agentService.completeTextOnly("", messages, 1800).block();

            PlanDefinition planDef = parseResponse(rawJson);

            if (planDef != null && planDef.tasks() != null && !planDef.tasks().isEmpty()) {
                int order = 1;
                for (AgentTaskDefinition taskDef :
                        planDef.tasks().stream().limit(MAX_TASKS).toList()) {
                    String title = bounded(taskDef.title(), MAX_TITLE_CHARS);
                    String details = bounded(taskDef.details(), MAX_DETAILS_CHARS);
                    if (title.isBlank() || details.isBlank()) {
                        continue;
                    }
                    AgentTask task = new AgentTask(
                            plan.getId(),
                            userId,
                            order++,
                            title,
                            details,
                            serializeTargetFiles(taskDef.targetFiles()),
                            AgentTask.STATUS_PENDING,
                            taskDef.needsApproval());
                    // Roteia a tarefa para o agente especializado mais adequado (ou o default).
                    AgentRoutingService.Routed routed = agentRoutingService.pick(userId, task);
                    task.setAssignedAgentId(routed.agent().getId());
                    task.setAgentRationale(routed.rationale());
                    taskRepository.save(task);
                }
                if (order == 1) {
                    createFallbackTask(plan, userId, request.goal());
                }
            } else {
                log.warn("Failed to parse plan definition. Creating a default single task.");
                createFallbackTask(plan, userId, request.goal());
            }

        } catch (Exception e) {
            log.error("Error generating tasks via LLM", e);
            createFallbackTask(plan, userId, request.goal());
        }

        return plan;
    }

    private void createFallbackTask(AgentPlan plan, UUID userId, String goal) {
        AgentTask task =
                new AgentTask(plan.getId(), userId, 1, "Execute Goal", goal, "", AgentTask.STATUS_PENDING, true);
        taskRepository.save(task);
    }

    private PlanDefinition parseResponse(String response) {
        try {
            String jsonStr = response;
            int start = jsonStr.indexOf("{");
            int end = jsonStr.lastIndexOf("}");
            if (start != -1 && end != -1) {
                jsonStr = jsonStr.substring(start, end + 1);
            }
            return objectMapper.readValue(jsonStr, PlanDefinition.class);
        } catch (Exception e) {
            log.warn("Could not parse JSON from model output: {}", response, e);
            return null;
        }
    }

    private String serializeRoots(List<String> roots) {
        if (roots == null || roots.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(roots);
        } catch (Exception e) {
            log.warn("Failed to serialize workspace roots", e);
            return null;
        }
    }

    private String serializeTargetFiles(JsonNode targetFiles) {
        List<String> files = new ArrayList<>();
        if (targetFiles != null && targetFiles.isArray()) {
            targetFiles.forEach(value -> {
                if (value.isTextual() && !value.asText().isBlank()) {
                    files.add(value.asText().trim());
                }
            });
        } else if (targetFiles != null
                && targetFiles.isTextual()
                && !targetFiles.asText().isBlank()) {
            files.add(targetFiles.asText().trim());
        }
        try {
            return objectMapper.writeValueAsString(
                    files.stream().distinct().limit(20).toList());
        } catch (Exception exception) {
            log.debug("Could not serialize target files", exception);
            return "[]";
        }
    }

    private String bounded(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip().replaceAll("\\s+", " ");
        return normalized.length() <= maxChars
                ? normalized
                : normalized.substring(0, maxChars).strip();
    }
}
