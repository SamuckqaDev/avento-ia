package com.avento.dto;

import java.util.Map;

public record CachedChunk(String id, String content, Map<String, Object> metadata) {}
