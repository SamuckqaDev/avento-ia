package com.avento.service.dto;

import java.util.UUID;

public record Context(UUID userId, Long chatId, String runId) {

    public Context {
        runId = runId == null ? "" : runId;
    }

    public static Context local() {
        return new Context(null, null, "");
    }

    public String scopeKey() {
        if (userId == null) {
            return com.avento.service.tools.ToolExecutionContext.ANONYMOUS_SCOPE;
        }
        return chatId == null ? "user:" + userId : "user:" + userId + ":chat:" + chatId;
    }
}
