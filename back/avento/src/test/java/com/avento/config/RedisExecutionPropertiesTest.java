package com.avento.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RedisExecutionPropertiesTest {

    @Test
    void createsAnIsolatedEventStreamForEachRun() {
        RedisExecutionProperties properties = new RedisExecutionProperties();
        properties.setEventStream("custom:events");
        properties.setEventTtl(Duration.ofHours(2));

        assertThat(properties.eventStreamKey("run_123")).isEqualTo("custom:events:run_123");
        assertThat(properties.getEventTtl()).isEqualTo(Duration.ofHours(2));
    }
}
