package com.avento.service.dto;

/** Resultado de reverter as mudanças de arquivo de uma run. */
public record RevertResult(String runId, int filesRestored) {}
