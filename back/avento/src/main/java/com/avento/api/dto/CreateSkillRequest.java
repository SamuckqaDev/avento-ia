package com.avento.api.dto;

import java.util.List;

public record CreateSkillRequest(String name, String description, List<String> triggers, String body) {}
