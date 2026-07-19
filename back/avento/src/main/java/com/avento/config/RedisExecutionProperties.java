package com.avento.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "avento.execution.redis")
public class RedisExecutionProperties {

    private boolean enabled;
    private String eventStream = "avento:events";
    private String agentJobStream = "avento:jobs:agent";
    private String deadLetterStream = "avento:dead-letter";
    private String agentConsumerGroup = "avento-agent-workers";
    private long eventMaxLength = 50_000;
    private Duration eventBlockTimeout = Duration.ofSeconds(2);
    private Duration eventTtl = Duration.ofHours(24);
    private Duration contextTtl = Duration.ofHours(24);
    private Duration runTimeout = Duration.ofMinutes(15);
    private Duration runInactivityTimeout = Duration.ofMinutes(3);
    private int contextMessageLimit = 20;

    public String eventStreamKey(String runId) {
        return eventStream + ":" + runId;
    }
}
