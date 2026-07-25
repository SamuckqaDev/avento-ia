package com.avento.api.dto;

import java.util.List;

public record CatalogRequest(List<String> serverIds, List<String> projectPaths, Long chatId) {
    public CatalogRequest {
        serverIds = serverIds == null ? List.of() : List.copyOf(serverIds);
        projectPaths = projectPaths == null ? List.of() : List.copyOf(projectPaths);
    }
}
