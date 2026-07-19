package com.avento.service.dto;

import java.util.Map;

public record CachedChunk(String id, String content, Map<String, Object> metadata) {}
