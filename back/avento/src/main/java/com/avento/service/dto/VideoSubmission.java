package com.avento.service.dto;

public record VideoSubmission(
        String promptId, String clientId, int width, int height, int frames, int fps, int steps) {}
