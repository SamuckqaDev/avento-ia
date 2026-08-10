package com.avento.dto;

/** Tokens agregados por modelo, separando entrada (prompt) e saída (completion). */
public interface ModelUsage {
    String getModel();

    long getPromptTokens();

    long getCompletionTokens();

    long getTotal();
}
