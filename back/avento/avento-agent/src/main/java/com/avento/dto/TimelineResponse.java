package com.avento.dto;

import java.util.List;

public record TimelineResponse(List<AgentTimelineItem> events) {}
