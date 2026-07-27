package com.avento.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.avento.api.ApiCodes;
import com.avento.auth.model.UserRole;
import com.avento.auth.security.AuthPrincipal;
import com.avento.model.AgentTimelineEvent;
import com.avento.service.AgentTimelineService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentTimelineControllerTest {

    @Test
    void listsTimelineEventsForRunWhenRunIdIsProvided() {
        AgentTimelineEvent event = new AgentTimelineEvent("run_123", "tool.completed", "read_file", "ok", "{}");
        event.setApprovalId("approval_123");
        TestTimelineService timelineService = new TestTimelineService(List.of(event));

        AgentTimelineController controller = new AgentTimelineController(timelineService);
        UUID userId = UUID.randomUUID();
        var response = controller.listTimeline(
                "run_123",
                new AuthPrincipal(userId, UUID.randomUUID(), "jti", "test@avento.local", "Test User", UserRole.USER));

        assertEquals(200, response.getBody().getStatus());
        assertEquals(ApiCodes.SUCCESS, response.getBody().getCode());
        var data = response.getBody().getData();
        assertEquals(1, data.events().size());
        assertEquals("run_123", data.events().get(0).runId());
        assertEquals("approval_123", data.events().get(0).approvalId());
        assertEquals("tool.completed", data.events().get(0).eventType());
        assertEquals("read_file", data.events().get(0).toolName());
        assertEquals("run_123", timelineService.lastRunId);
    }

    private static class TestTimelineService extends AgentTimelineService {
        private final List<AgentTimelineEvent> events;
        private String lastRunId;

        private TestTimelineService(List<AgentTimelineEvent> events) {
            super(Optional.empty());
            this.events = events;
        }

        @Override
        public List<AgentTimelineEvent> eventsForRun(UUID userId, String runId) {
            lastRunId = runId;
            return events;
        }
    }
}
