package com.avento.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.config.RedisExecutionProperties;
import com.avento.model.AgentRunJob;
import com.avento.repository.AgentRunJobRepository;
import com.avento.repository.AgentTimelineEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class RunEventStreamServiceTest {

    @Test
    void skipsARequiredApprovalThatWasAlreadyResolved() {
        UUID userId = UUID.randomUUID();
        AgentRunJobRepository jobs = mock(AgentRunJobRepository.class);
        AgentTimelineEventRepository timeline = mock(AgentTimelineEventRepository.class);
        AgentRunJob completed = new AgentRunJob();
        completed.setStatus(AgentRunJob.Status.COMPLETED);
        when(jobs.findByRunIdAndUserId("run_1", userId)).thenReturn(Optional.of(completed));
        ApprovalReplayGuard guard = new ApprovalReplayGuard(jobs, timeline);
        RunEventStreamService service = service(guard);
        String raw = "{\"avento_event\":{\"type\":\"tool.approval.required\",\"approvalId\":\"approval_1\"}}";

        assertThat(service.shouldDeliver(userId, "run_1", "tool.approval.required", raw))
                .isFalse();
        assertThat(service.shouldDeliver(userId, "run_1", "tool.completed", "{}"))
                .isTrue();
    }

    private RunEventStreamService service(ApprovalReplayGuard guard) {
        ObjectProvider<StringRedisTemplate> provider =
                new StaticListableBeanFactory().getBeanProvider(StringRedisTemplate.class);
        return new RunEventStreamService(provider, new RedisExecutionProperties(), guard, new ObjectMapper());
    }
}
