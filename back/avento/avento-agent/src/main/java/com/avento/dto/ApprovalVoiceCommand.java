package com.avento.dto;

import com.avento.service.agent.AgentService.ApprovalVoiceDecision;

public record ApprovalVoiceCommand(
        String approvalId, ApprovalVoiceDecision decision, ApprovalMemory memory, String comment) {}
