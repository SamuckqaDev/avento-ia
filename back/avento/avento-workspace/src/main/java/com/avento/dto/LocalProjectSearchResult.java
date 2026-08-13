package com.avento.dto;

import java.util.List;

/** Resultado limitado de uma busca local por pastas de projeto. */
public record LocalProjectSearchResult(String query, List<LocalProjectMatch> matches, boolean truncated) {}
