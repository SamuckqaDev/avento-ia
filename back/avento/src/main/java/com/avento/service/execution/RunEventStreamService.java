package com.avento.service.execution;

import com.avento.config.RedisExecutionProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Service
public class RunEventStreamService {

    private static final Logger logger = LoggerFactory.getLogger(RunEventStreamService.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisExecutionProperties properties;
    private final ApprovalReplayGuard approvalReplayGuard;
    private final ObjectMapper mapper;

    public RunEventStreamService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            RedisExecutionProperties properties,
            ApprovalReplayGuard approvalReplayGuard,
            ObjectMapper mapper) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.properties = properties;
        this.approvalReplayGuard = approvalReplayGuard;
        this.mapper = mapper;
    }

    public Flux<ServerSentEvent<String>> stream(UUID userId, String runId, String afterEventId) {
        if (!properties.isEnabled() || redisTemplate == null) {
            return Flux.error(new IllegalStateException("Redis execution streaming is disabled"));
        }

        String owner = userId == null ? "local" : userId.toString();
        String initialOffset = afterEventId == null || afterEventId.isBlank() ? "0-0" : afterEventId;
        String eventStreamKey = properties.eventStreamKey(runId);

        return Flux.<ServerSentEvent<String>>create(sink -> {
            AtomicBoolean cancelled = new AtomicBoolean();
            Disposable reader = Schedulers.boundedElastic().schedule(() -> {
                String offset = initialOffset;
                try {
                    while (!cancelled.get() && !sink.isCancelled()) {
                        StreamOperations<String, String, String> streamOperations = redisTemplate.opsForStream();
                        List<MapRecord<String, String, String>> records = streamOperations.read(
                                StreamReadOptions.empty().count(100).block(properties.getEventBlockTimeout()),
                                StreamOffset.create(eventStreamKey, ReadOffset.from(offset)));
                        if (records == null || records.isEmpty()) {
                            continue;
                        }
                        for (MapRecord<String, String, String> record : records) {
                            offset = record.getId().getValue();
                            Map<String, String> values = record.getValue();
                            if (!owner.equals(value(values, "userId")) || !runId.equals(value(values, "runId"))) {
                                continue;
                            }
                            String type = value(values, "type");
                            String raw = value(values, "raw");
                            if (!shouldDeliver(userId, runId, type, raw)) {
                                continue;
                            }
                            sink.next(ServerSentEvent.<String>builder(raw)
                                    .id(offset)
                                    .event(type)
                                    .build());
                            if (closesConnection(type)) {
                                sink.complete();
                                return;
                            }
                        }
                    }
                } catch (RuntimeException exception) {
                    if (!cancelled.get() && !sink.isCancelled()) {
                        logger.debug("Redis event reader failed for run {}", runId, exception);
                        sink.error(exception);
                    }
                }
            });
            sink.onCancel(() -> {
                cancelled.set(true);
                reader.dispose();
            });
            sink.onDispose(() -> {
                cancelled.set(true);
                reader.dispose();
            });
        });
    }

    private String value(Map<String, String> values, String key) {
        return values.getOrDefault(key, "");
    }

    private boolean closesConnection(String type) {
        return "agent.run.completed".equals(type)
                || "agent.run.failed".equals(type)
                || "agent.run.cancelled".equals(type)
                || "tool.approval.required".equals(type);
    }

    boolean shouldDeliver(UUID userId, String runId, String type, String raw) {
        if (!"tool.approval.required".equals(type)) {
            return true;
        }
        try {
            JsonNode event = mapper.readTree(raw).path("avento_event");
            String approvalId = event.path("approvalId").asText("");
            return approvalId.isBlank() || approvalReplayGuard.isPending(userId, runId, approvalId);
        } catch (Exception exception) {
            logger.debug("Could not inspect approval event while replaying run {}", runId, exception);
            return true;
        }
    }
}
