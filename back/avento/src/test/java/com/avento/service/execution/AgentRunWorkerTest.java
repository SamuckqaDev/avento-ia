package com.avento.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.avento.service.dto.AgentRunSnapshot;
import com.avento.service.orchestration.AgentRunRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class AgentRunWorkerTest {

    @Test
    void recognizesBusyGroupInANestedRedisException() {
        RuntimeException error = new RuntimeException(
                "Redis initialization failed",
                new IllegalStateException("BUSYGROUP Consumer Group name already exists"));

        assertThat(AgentRunWorker.consumerGroupAlreadyExists(error)).isTrue();
        assertThat(AgentRunWorker.consumerGroupAlreadyExists(new RuntimeException("connection refused")))
                .isFalse();
    }

    @Test
    void recognizesTimeoutInANestedExecutionError() {
        RuntimeException error = new RuntimeException("worker failed", new TimeoutException("run timed out"));

        assertThat(AgentRunWorker.causedByTimeout(error)).isTrue();
        assertThat(AgentRunWorker.causedByTimeout(new RuntimeException("model failed")))
                .isFalse();
    }

    @Test
    void detectsRunsWhoseLastActivityExceededTheWatchdogThreshold() {
        LocalDateTime now = LocalDateTime.now();
        AgentRunSnapshot stale = new AgentRunSnapshot(
                "run_stale",
                "Analyze",
                List.of(),
                AgentRunRegistry.AgentRunStatus.RUNNING,
                "agent.round.started",
                null,
                now.minusMinutes(4),
                now.minusMinutes(4));

        assertThat(AgentRunWorker.isInactive(stale, now.minusMinutes(3))).isTrue();
        assertThat(AgentRunWorker.isInactive(stale, now.minusMinutes(5))).isFalse();
    }
}
