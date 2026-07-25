package com.avento.service.dto;

import java.util.List;

/**
 * Uma skill carregada de agent/skills/*.md. Alem do corpo (procedimento injetado no
 * modelo), o cabecalho pode declarar `Gatilhos:` (frases que ativam a skill sem barra)
 * e `Ferramenta:` (a ferramenta que a skill comanda — invocacao explicita com argumento
 * vira chamada direta dessa ferramenta, sem depender do modelo escolher).
 */
public record Skill(
        String name,
        String description,
        List<String> triggers,
        String tool,
        List<String> tools,
        Integer maxRounds,
        String body,
        boolean builtin) {

    public boolean declaresTool() {
        return (tool != null && !tool.isBlank()) || (tools != null && !tools.isEmpty());
    }
}
