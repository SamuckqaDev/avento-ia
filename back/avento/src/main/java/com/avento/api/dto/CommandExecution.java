package com.avento.api.dto;

import java.util.List;

public record CommandExecution(
        List<String> command, int exitCode, boolean timedOut, double durationSeconds, String output) {}
