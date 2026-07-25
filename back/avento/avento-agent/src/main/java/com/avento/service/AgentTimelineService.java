package com.avento.service;

import com.avento.model.AgentTimelineEvent;
import com.avento.repository.AgentTimelineEventRepository;
import com.avento.service.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AgentTimelineService {

    private static final Logger logger = LoggerFactory.getLogger(AgentTimelineService.class);
    private static final int MAX_DETAIL_CHARS = 1000;
    private static final int MAX_PAYLOAD_CHARS = 6000;
    private static final int MAX_VALUE_CHARS = 1000;

    private final Optional<AgentTimelineEventRepository> repository;
    private final Map<String, RunOwner> runOwners = new ConcurrentHashMap<>();

    public AgentTimelineService(Optional<AgentTimelineEventRepository> repository) {
        this.repository = repository;
    }

    public void registerRun(String runId, UUID userId, Long chatId) {
        if (runId != null && !runId.isBlank() && userId != null) {
            runOwners.put(runId, new RunOwner(userId, chatId));
        }
    }

    public void record(String runId, String eventType, String toolName, String detail, JsonNode payload) {
        if (runId == null || runId.isBlank() || eventType == null || eventType.isBlank()) {
            return;
        }

        repository.ifPresent(repo -> {
            try {
                RunOwner owner = runOwners.get(runId);
                AgentTimelineEvent event = new AgentTimelineEvent(
                        runId,
                        owner == null ? null : owner.userId(),
                        owner == null ? null : owner.chatId(),
                        eventType,
                        blankToNull(toolName),
                        sanitizeDetail(detail),
                        payload(payload));
                repo.save(event);
            } catch (RuntimeException e) {
                logger.debug("Could not persist agent timeline event {}", eventType, e);
            }
        });
    }

    public void recordApproval(
            String runId, String approvalId, String eventType, String toolName, String detail, JsonNode payload) {
        if (runId == null || runId.isBlank() || eventType == null || eventType.isBlank()) {
            return;
        }

        repository.ifPresent(repo -> {
            try {
                RunOwner owner = runOwners.get(runId);
                AgentTimelineEvent event = new AgentTimelineEvent(
                        runId,
                        owner == null ? null : owner.userId(),
                        owner == null ? null : owner.chatId(),
                        eventType,
                        blankToNull(toolName),
                        sanitizeDetail(detail),
                        payload(payload));
                event.setApprovalId(blankToNull(approvalId));
                repo.save(event);
            } catch (RuntimeException e) {
                logger.debug("Could not persist agent approval timeline event {}", eventType, e);
            }
        });
    }

    public List<AgentTimelineEvent> recentEvents() {
        return repository
                .map(AgentTimelineEventRepository::findTop100ByOrderByCreatedAtDesc)
                .orElseGet(List::of);
    }

    public List<AgentTimelineEvent> eventsForRun(String runId) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        return repository
                .map(repo -> repo.findByRunIdOrderByCreatedAtAsc(runId))
                .orElseGet(List::of);
    }

    public List<AgentTimelineEvent> recentEvents(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return repository
                .map(repo -> repo.findTop100ByUserIdOrderByCreatedAtDesc(userId))
                .orElseGet(List::of);
    }

    public List<AgentTimelineEvent> eventsForRun(UUID userId, String runId) {
        if (userId == null || runId == null || runId.isBlank()) {
            return List.of();
        }
        return repository
                .map(repo -> repo.findByUserIdAndRunIdOrderByCreatedAtAsc(userId, runId))
                .orElseGet(List::of);
    }

    private String payload(JsonNode payload) {
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return null;
        }
        return truncate(sanitize(payload.deepCopy(), null).toString(), MAX_PAYLOAD_CHARS);
    }

    private JsonNode sanitize(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (isSensitiveField(fieldName)) {
            return TextNode.valueOf("[redacted]");
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                object.set(field.getKey(), sanitize(field.getValue(), field.getKey()));
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int index = 0; index < array.size(); index++) {
                array.set(index, sanitize(array.get(index), fieldName));
            }
            return array;
        }
        if (node.isTextual()) {
            String value = node.asText();
            if (isLargeContentField(fieldName) && value.length() > 160) {
                return TextNode.valueOf("[omitted: " + value.length() + " chars]");
            }
            if (value.startsWith("data:") || value.length() > MAX_VALUE_CHARS) {
                return TextNode.valueOf(
                        truncate(value.startsWith("data:") ? "[binary data omitted]" : value, MAX_VALUE_CHARS));
            }
        }
        return node;
    }

    private boolean isSensitiveField(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String normalized = fieldName.toLowerCase();
        return normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("cookie")
                || normalized.contains("authorization")
                || normalized.contains("api_key")
                || normalized.contains("apikey")
                || normalized.contains("dsn");
    }

    private boolean isLargeContentField(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String normalized = fieldName.toLowerCase();
        return normalized.equals("content")
                || normalized.equals("output")
                || normalized.equals("result")
                || normalized.equals("body")
                || normalized.contains("base64")
                || normalized.contains("filecontent")
                || normalized.contains("document");
    }

    private String sanitizeDetail(String detail) {
        if (detail == null) {
            return null;
        }
        String sanitized = detail.replaceAll(
                        "(?i)(authorization|token|password|secret|cookie|dsn)\\s*[:=]\\s*[^,;\\s]+", "$1=[redacted]")
                .replaceAll("data:[^;]+;base64,[A-Za-z0-9+/=]+", "[binary data omitted]");
        return truncate(sanitized, MAX_DETAIL_CHARS);
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars - 16) + "\n...[truncated]";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
