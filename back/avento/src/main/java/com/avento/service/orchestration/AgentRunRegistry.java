package com.avento.service.orchestration;

import com.avento.service.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Bounded live state for active agent runs; durable details remain in the timeline table. */
@Component
public class AgentRunRegistry {

    private static final int MAX_RUNS = 200;

    private final ObjectMapper mapper;
    private final Map<String, AgentRunSnapshot> runs = new ConcurrentHashMap<>();
    private final Map<String, String> approvalRuns = new ConcurrentHashMap<>();
    private final Map<String, UUID> runOwners = new ConcurrentHashMap<>();

    public AgentRunRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AgentRunSnapshot start(String runId, String objective, List<String> workspaceRoots) {
        return start(runId, objective, workspaceRoots, null);
    }

    public AgentRunSnapshot start(String runId, String objective, List<String> workspaceRoots, UUID userId) {
        LocalDateTime now = LocalDateTime.now();
        AgentRunSnapshot snapshot = new AgentRunSnapshot(
                runId,
                trimObjective(objective),
                workspaceRoots == null ? List.of() : List.copyOf(workspaceRoots),
                AgentRunStatus.RUNNING,
                "agent.run.started",
                null,
                now,
                now);
        runs.put(runId, snapshot);
        if (userId != null) {
            runOwners.put(runId, userId);
        }
        evictOldRuns();
        return snapshot;
    }

    public void observe(String runId, String chunk) {
        AgentRunSnapshot current = runs.get(runId);
        if (current == null || chunk == null || chunk.isBlank()) {
            return;
        }
        try {
            JsonNode event = mapper.readTree(chunk).path("avento_event");
            if (!event.isObject()) {
                touch(runId);
                return;
            }
            String type = event.path("type").asText("");
            String approvalId = event.path("approvalId").asText("");
            AgentRunStatus status = statusFor(type, current.status());
            String storedApproval = approvalId.isBlank() ? current.approvalId() : approvalId;
            if (!approvalId.isBlank()) {
                approvalRuns.put(approvalId, runId);
            }
            runs.put(runId, current.with(status, type, storedApproval));
        } catch (Exception ignored) {
            touch(runId);
        }
    }

    public void resumeApproval(String approvalId) {
        findRunIdByApproval(approvalId)
                .ifPresent(runId -> updateStatus(runId, AgentRunStatus.RUNNING, "approval.resumed"));
    }

    public void finish(String runId) {
        AgentRunSnapshot current = runs.get(runId);
        if (current != null
                && current.status() != AgentRunStatus.AWAITING_APPROVAL
                && current.status() != AgentRunStatus.FAILED
                && current.status() != AgentRunStatus.CANCELLED) {
            updateStatus(runId, AgentRunStatus.COMPLETED, "agent.run.completed");
        }
    }

    public void fail(String runId) {
        updateStatus(runId, AgentRunStatus.FAILED, "agent.run.failed");
    }

    public Optional<AgentRunSnapshot> find(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    public Optional<AgentRunSnapshot> findOwned(String runId, UUID userId) {
        if (userId == null || !userId.equals(runOwners.get(runId))) {
            return Optional.empty();
        }
        return find(runId);
    }

    public Optional<String> findRunIdByApproval(String approvalId) {
        return Optional.ofNullable(approvalRuns.get(approvalId));
    }

    public List<AgentRunSnapshot> recent() {
        return runs.values().stream()
                .sorted(Comparator.comparing(AgentRunSnapshot::updatedAt).reversed())
                .limit(50)
                .toList();
    }

    public List<AgentRunSnapshot> recent(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return runs.values().stream()
                .filter(run -> userId.equals(runOwners.get(run.runId())))
                .sorted(Comparator.comparing(AgentRunSnapshot::updatedAt).reversed())
                .limit(50)
                .toList();
    }

    private void updateStatus(String runId, AgentRunStatus status, String eventType) {
        runs.computeIfPresent(runId, (ignored, current) -> current.with(status, eventType, current.approvalId()));
    }

    private void touch(String runId) {
        runs.computeIfPresent(runId, (ignored, current) -> current.touch());
    }

    private AgentRunStatus statusFor(String type, AgentRunStatus current) {
        return switch (type) {
            case "tool.approval.required" -> AgentRunStatus.AWAITING_APPROVAL;
            case "agent.error", "tool.failed" -> AgentRunStatus.FAILED;
            case "tool.rejected" -> AgentRunStatus.CANCELLED;
            case "tool.started", "tool.approval.accepted", "agent.round.started", "agent.round.retry" ->
                AgentRunStatus.RUNNING;
            case "agent.round.completed" ->
                current == AgentRunStatus.FAILED ? AgentRunStatus.FAILED : AgentRunStatus.COMPLETED;
            default -> current;
        };
    }

    private void evictOldRuns() {
        if (runs.size() <= MAX_RUNS) {
            return;
        }
        runs.values().stream()
                .min(Comparator.comparing(AgentRunSnapshot::updatedAt))
                .ifPresent(oldest -> {
                    runs.remove(oldest.runId());
                    runOwners.remove(oldest.runId());
                    approvalRuns.entrySet().removeIf(entry -> oldest.runId().equals(entry.getValue()));
                });
    }

    private String trimObjective(String objective) {
        if (objective == null) {
            return "";
        }
        String clean = objective.trim().replaceAll("\\s+", " ");
        return clean.length() <= 240 ? clean : clean.substring(0, 237) + "...";
    }

    public enum AgentRunStatus {
        RUNNING,
        AWAITING_APPROVAL,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
