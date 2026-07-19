package com.avento.service.execution;

import com.avento.config.RedisExecutionProperties;
import com.avento.model.ExecutionOutboxEvent;
import com.avento.repository.ExecutionOutboxEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RedisOutboxDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(RedisOutboxDispatcher.class);

    private final ExecutionOutboxEventRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper mapper;
    private final RedisExecutionProperties properties;

    public RedisOutboxDispatcher(
            ExecutionOutboxEventRepository repository,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper mapper,
            RedisExecutionProperties properties) {
        this.repository = repository;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.mapper = mapper;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${avento.execution.redis.outbox-delay-ms:250}")
    public void publishPending() {
        if (!properties.isEnabled() || redisTemplate == null) {
            return;
        }
        for (ExecutionOutboxEvent event : repository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()) {
            publish(event);
        }
    }

    @Scheduled(fixedDelayString = "${avento.execution.redis.outbox-cleanup-delay-ms:3600000}")
    @Transactional
    public void removePublishedHistory() {
        repository.deleteByPublishedAtBefore(LocalDateTime.now().minusDays(1));
    }

    private void publish(ExecutionOutboxEvent event) {
        try {
            JsonNode payload = mapper.readTree(event.getPayload());
            Map<String, String> values = new LinkedHashMap<>();
            values.put("outboxId", event.getId().toString());
            values.put("aggregateId", event.getAggregateId());
            values.put("jobId", payload.path("jobId").asText());
            values.put("runId", payload.path("runId").asText());
            MapRecord<String, String, String> record =
                    StreamRecords.string(values).withStreamKey(event.getStreamKey());
            redisTemplate.opsForStream().add(record);
            event.setPublishedAt(LocalDateTime.now());
            event.setLastError(null);
            repository.save(event);
        } catch (Exception exception) {
            event.setAttempts(event.getAttempts() + 1);
            event.setLastError(exception.getMessage());
            repository.save(event);
            logger.debug("Could not publish outbox event {}", event.getId(), exception);
        }
    }
}
