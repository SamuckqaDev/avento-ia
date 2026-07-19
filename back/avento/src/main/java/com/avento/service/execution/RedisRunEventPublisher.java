package com.avento.service.execution;

import com.avento.config.RedisExecutionProperties;
import com.avento.service.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisRunEventPublisher implements RunEventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(RedisRunEventPublisher.class);
    private static final int MAX_RAW_EVENT_CHARS = 64_000;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper mapper;
    private final RedisExecutionProperties properties;
    private final Map<String, RunScope> scopes = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> runEventCounts = new ConcurrentHashMap<>();
    private final AtomicBoolean redisFailureLogged = new AtomicBoolean();

    public RedisRunEventPublisher(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper mapper,
            RedisExecutionProperties properties) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public void publish(String runId, UUID userId, Long chatId, String rawEvent) {
        if (!properties.isEnabled() || redisTemplate == null || runId == null || runId.isBlank()) {
            return;
        }
        if (userId != null) {
            scopes.put(runId, new RunScope(userId, chatId));
        }
        RunScope scope = scopes.get(runId);
        UUID effectiveUserId = userId != null ? userId : scope == null ? null : scope.userId();
        Long effectiveChatId = chatId != null ? chatId : scope == null ? null : scope.chatId();

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("runId", runId);
        fields.put("userId", effectiveUserId == null ? "local" : effectiveUserId.toString());
        fields.put("chatId", effectiveChatId == null ? "" : effectiveChatId.toString());
        fields.put("type", eventType(rawEvent));
        fields.put("timestamp", Instant.now().toString());
        fields.put("raw", truncate(rawEvent));

        try {
            String streamKey = properties.eventStreamKey(runId);
            MapRecord<String, String, String> record =
                    StreamRecords.string(fields).withStreamKey(streamKey);
            redisTemplate.opsForStream().add(record);
            long eventCount = runEventCounts
                    .computeIfAbsent(runId, ignored -> new AtomicLong())
                    .incrementAndGet();
            boolean terminalEvent = terminal(fields.get("type"));
            if (eventCount % 100 == 0) {
                redisTemplate.opsForStream().trim(streamKey, properties.getEventMaxLength(), true);
            }
            if (eventCount == 1 || terminalEvent) {
                redisTemplate.expire(streamKey, properties.getEventTtl());
            }
            if (terminalEvent) {
                scopes.remove(runId);
                runEventCounts.remove(runId);
            }
            redisFailureLogged.set(false);
        } catch (RuntimeException exception) {
            if (redisFailureLogged.compareAndSet(false, true)) {
                logger.warn("Redis event stream is unavailable; the direct response stream will continue", exception);
            }
        }
    }

    private String eventType(String rawEvent) {
        if (rawEvent == null || rawEvent.isBlank()) {
            return "model.delta";
        }
        try {
            JsonNode parsed = mapper.readTree(rawEvent);
            String type = parsed.path("avento_event").path("type").asText("");
            return type.isBlank() ? "model.delta" : type;
        } catch (Exception ignored) {
            return "model.delta";
        }
    }

    private String truncate(String rawEvent) {
        if (rawEvent == null) {
            return "";
        }
        return rawEvent.length() <= MAX_RAW_EVENT_CHARS
                ? rawEvent
                : rawEvent.substring(0, MAX_RAW_EVENT_CHARS) + "\n...[truncated]";
    }

    private boolean terminal(String type) {
        return "agent.run.completed".equals(type)
                || "agent.run.failed".equals(type)
                || "agent.run.cancelled".equals(type);
    }
}
