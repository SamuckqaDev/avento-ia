package com.avento.service.dto;

import java.util.Locale;

public record TranscriptionResult(String text, String detectedLanguage) {
    public boolean placeholder() {
        return INVALID_TRANSCRIPT_PLACEHOLDERS.contains(text.toLowerCase(Locale.ROOT)
                .replaceAll("[()\\[\\].,!?:;\"']", " ")
                .replaceAll("\\s+", " ")
                .trim());
    }

    public static final java.util.Set<String> INVALID_TRANSCRIPT_PLACEHOLDERS =
            java.util.Set.of("[BLANK_AUDIO]", "[silence]");

    public TranscriptionResult withoutPlaceholder() {
        return placeholder() ? new TranscriptionResult("", detectedLanguage) : this;
    }
}
