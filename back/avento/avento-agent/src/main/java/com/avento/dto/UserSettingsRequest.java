package com.avento.dto;

public record UserSettingsRequest(Boolean ttsEnabled, Boolean thinkingEnabled, Boolean autoApproveAll) {}
