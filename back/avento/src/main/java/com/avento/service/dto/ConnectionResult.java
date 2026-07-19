package com.avento.service.dto;

import java.util.List;

public record ConnectionResult(boolean connected, String serverName, List<ToolDefinition> tools, String error) {
    public static ConnectionResult failed(String error) {
        return new ConnectionResult(false, "", List.of(), error);
    }

    public static ConnectionResult failedFor(String serverName, String error) {
        return new ConnectionResult(false, serverName, List.of(), error);
    }
}
