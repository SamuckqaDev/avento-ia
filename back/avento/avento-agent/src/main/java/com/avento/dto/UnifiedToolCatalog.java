package com.avento.dto;

import java.util.List;

/** Resposta leve do catálogo unificado de ferramentas e servidores. */
public record UnifiedToolCatalog(List<UnifiedToolCatalogEntry> entries) {}
