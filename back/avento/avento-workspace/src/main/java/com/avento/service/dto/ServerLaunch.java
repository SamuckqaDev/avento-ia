package com.avento.service.dto;

import java.util.List;
import java.util.Map;

public record ServerLaunch(boolean ready, List<String> command, Map<String, String> environment, String reason) {
    public static ServerLaunch ready(List<String> command, Map<String, String> environment) {
        return new ServerLaunch(true, List.copyOf(command), Map.copyOf(environment), "");
    }

    public static ServerLaunch unavailable(String reason) {
        return new ServerLaunch(false, List.of(), Map.of(), reason);
    }
}
