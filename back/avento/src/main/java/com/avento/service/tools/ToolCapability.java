package com.avento.service.tools;

public record ToolCapability(
        String name,
        ToolCategory category,
        ToolRiskLevel riskLevel,
        ToolApprovalPolicy approvalPolicy,
        boolean directAutoExecutable,
        String summary) {

    public boolean requiresApproval() {
        return approvalPolicy == ToolApprovalPolicy.APPROVAL_REQUIRED;
    }
}
