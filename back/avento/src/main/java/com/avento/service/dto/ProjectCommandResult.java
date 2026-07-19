package com.avento.service.dto;

public record ProjectCommandResult(
        String runner,
        String name,
        String command,
        int exitCode,
        boolean timedOut,
        double durationSeconds,
        String output,
        String finishedAt) {}
