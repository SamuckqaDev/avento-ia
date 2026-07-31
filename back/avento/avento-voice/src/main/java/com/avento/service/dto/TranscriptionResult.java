package com.avento.service.dto;

import java.util.Locale;
import java.util.Set;

public record TranscriptionResult(String text, String detectedLanguage) {
    public boolean placeholder() {
        return INVALID_TRANSCRIPT_PLACEHOLDERS.contains(text.toLowerCase(Locale.ROOT)
                .replaceAll("[()\\[\\].,!?:;\"']", " ")
                .replaceAll("\\s+", " ")
                .trim());
    }

    public static final Set<String> INVALID_TRANSCRIPT_PLACEHOLDERS =
            Set.of("[BLANK_AUDIO]", "[silence]");

    public TranscriptionResult withoutPlaceholder() {
        return placeholder() ? new TranscriptionResult("", detectedLanguage) : this;
    }
}
