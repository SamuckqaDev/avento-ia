package com.avento.dto;

/** Durable user-facing outcome of an asynchronous agent run. */
public record AgentRunResult(String runId, Long chatId, String status, Long messageId, String content) {}
