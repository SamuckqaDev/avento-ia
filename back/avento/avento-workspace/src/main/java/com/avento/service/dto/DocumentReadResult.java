package com.avento.service.dto;

public record DocumentReadResult(
        String path, String mediaType, long bytes, String reader, String content, boolean truncated) {}
