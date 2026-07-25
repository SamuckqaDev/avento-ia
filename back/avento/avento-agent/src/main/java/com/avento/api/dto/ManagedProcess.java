package com.avento.api.dto;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public record ManagedProcess(
        String processId,
        UUID ownerId,
        Process process,
        Path workingDirectory,
        List<String> command,
        String startedAt,
        StringBuilder logs) {

    public static final int MAX_PROCESS_LOG_CHARS = 40000;

    public synchronized void append(String text) {
        logs.append(text);
        if (logs.length() > MAX_PROCESS_LOG_CHARS) {
            logs.delete(0, logs.length() - MAX_PROCESS_LOG_CHARS);
        }
    }

    public synchronized String tail(int maxChars) {
        if (logs.length() <= maxChars) {
            return logs.toString();
        }
        return logs.substring(logs.length() - maxChars);
    }
}
