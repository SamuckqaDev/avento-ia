package com.avento.api.dto;

import java.util.List;

public record TimelineResponse(List<AgentTimelineItem> events) {}
