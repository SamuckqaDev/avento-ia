package com.avento.service.image;

public record ImagePromptPlan(
        String originalPrompt, String positivePrompt, String negativePrompt, int subjectCount, boolean humanSubject) {}
