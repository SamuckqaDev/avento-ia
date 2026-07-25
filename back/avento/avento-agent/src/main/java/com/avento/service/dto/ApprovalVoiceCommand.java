package com.avento.service.dto;

import com.avento.service.AgentService.ApprovalVoiceDecision;

public record ApprovalVoiceCommand(
        String approvalId, ApprovalVoiceDecision decision, ApprovalMemory memory, String comment) {}
