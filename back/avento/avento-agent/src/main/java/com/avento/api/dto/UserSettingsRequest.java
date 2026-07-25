package com.avento.api.dto;

public record UserSettingsRequest(Boolean ttsEnabled, Boolean thinkingEnabled, Boolean autoApproveAll) {}
