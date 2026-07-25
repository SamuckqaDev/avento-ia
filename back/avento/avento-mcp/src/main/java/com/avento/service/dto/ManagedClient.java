package com.avento.service.dto;

import io.modelcontextprotocol.client.McpSyncClient;
import java.util.List;

public record ManagedClient(ClientKey key, McpSyncClient client, List<String> tools) {}
