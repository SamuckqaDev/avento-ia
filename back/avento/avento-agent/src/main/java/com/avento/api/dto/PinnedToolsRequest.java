package com.avento.api.dto;

import java.util.List;

/**
 * Lista completa de ferramentas fixadas.
 *
 * <p>Estado inteiro, não incremento: a tela sempre sabe o conjunto todo, e mandar o todo evita a
 * classe de bug em que marcar e desmarcar rápido deixa o servidor com uma lista que a tela não
 * mostra.
 */
public record PinnedToolsRequest(List<String> toolNames) {}
