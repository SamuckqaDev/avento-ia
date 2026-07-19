package com.avento.service.dto;

import java.util.List;

public record SystemActionResult(
        String status, List<String> command, int exitCode, boolean timedOut, double durationSeconds, String output) {}
