package com.avento.service.dto;

import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;

public record SkillResolution(
        boolean invoked,
        boolean found,
        String skillName,
        String toolName,
        List<String> tools,
        Integer maxRounds,
        String argument,
        ArrayNode augmentedMessages,
        String notFoundReply) {

    public static final SkillResolution NOT_INVOKED =
            new SkillResolution(false, false, null, null, null, null, null, null, null);

    public boolean declaresTool() {
        return (toolName != null && !toolName.isBlank()) || (tools != null && !tools.isEmpty());
    }
}
