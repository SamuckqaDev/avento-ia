package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.*;
import com.avento.api.dto.BaseResponse;
import com.avento.auth.security.AuthPrincipal;
import com.avento.service.AgentTimelineService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/timeline")
public class AgentTimelineController {

    private final AgentTimelineService timelineService;

    public AgentTimelineController(AgentTimelineService timelineService) {
        this.timelineService = timelineService;
    }

    @GetMapping
    public ResponseEntity<BaseResponse<TimelineResponse>> listTimeline(
            @RequestParam(required = false) String runId, @AuthenticationPrincipal AuthPrincipal principal) {
        UUID userId = principal == null ? null : principal.userId();
        List<AgentTimelineItem> events = (runId == null || runId.isBlank()
                        ? timelineService.recentEvents(userId)
                        : timelineService.eventsForRun(userId, runId))
                .stream().map(AgentTimelineItem::from).toList();
        return ApiResponses.ok(new TimelineResponse(events));
    }
}
