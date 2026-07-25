package com.avento.service.dto;

/** Uma definição de símbolo encontrada: arquivo, linha (1-based) e o texto da linha. */
public record SymbolMatch(String file, int line, String text) {}
