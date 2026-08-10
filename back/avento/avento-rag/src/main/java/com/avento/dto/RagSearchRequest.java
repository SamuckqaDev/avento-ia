package com.avento.dto;

import java.util.List;

public record RagSearchRequest(String query, List<String> projectPaths) {}
