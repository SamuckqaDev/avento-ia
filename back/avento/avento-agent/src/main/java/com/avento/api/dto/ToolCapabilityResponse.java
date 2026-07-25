package com.avento.api.dto;

import com.avento.service.tools.ToolApprovalPolicy;
import com.avento.service.tools.ToolCapability;
import com.avento.service.tools.ToolCategory;
import com.avento.service.tools.ToolRiskLevel;

public record ToolCapabilityResponse(
        String name,
        ToolCategory category,
        ToolRiskLevel riskLevel,
        ToolApprovalPolicy approvalPolicy,
        boolean directAutoExecutable,
        String summary) {

    public static ToolCapabilityResponse from(ToolCapability capability) {
        return new ToolCapabilityResponse(
                capability.name(),
                capability.category(),
                capability.riskLevel(),
                capability.approvalPolicy(),
                capability.directAutoExecutable(),
                capability.summary());
    }
}
