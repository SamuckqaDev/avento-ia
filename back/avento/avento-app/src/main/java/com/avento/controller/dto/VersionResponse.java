package com.avento.controller.dto;

import java.time.Instant;

public record VersionResponse(String version, Instant buildTime) {}
