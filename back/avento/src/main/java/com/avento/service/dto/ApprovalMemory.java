package com.avento.service.dto;

import java.time.Duration;

public record ApprovalMemory(Duration duration, boolean always, String label) {
    public static ApprovalMemory once() {
        return new ApprovalMemory(null, false, "so agora");
    }
}
