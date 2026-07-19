package com.avento.service.dto;

import java.util.Map;

public record Manifest(String projectRoot, Map<String, FileManifest> files) {}
