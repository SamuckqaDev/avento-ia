package com.avento.api.dto;

public record DocumentUploadResult(
        String name, String mediaType, long bytes, String reader, String content, boolean truncated) {}
