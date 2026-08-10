package com.avento.dto;

import java.util.List;

public record FileManifest(String fileHash, List<String> chunkIds) {}
