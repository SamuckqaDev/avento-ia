package com.avento.service.dto;

import java.nio.file.Path;
import java.util.Map;

public record DatabaseConfiguration(Path configFile, Map<String, String> environment, String source) {
    public DatabaseConfiguration {
        environment = Map.copyOf(environment);
    }
}
