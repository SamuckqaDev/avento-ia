package com.avento.service.tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deixa o schema gerado pelo {@code @Tool} do Spring AI com a MESMA forma do schema artesanal.
 *
 * <h2>Por que isto precisa existir</h2>
 *
 * O gerador do Spring AI devolve JSON formatado e com a chave {@code $schema}. Medido sobre o
 * {@code read_file}:
 *
 * <pre>
 *   artesanal          162 chars
 *   gerado como vem    280 chars   (+72%)
 *   gerado normalizado 162 chars   (idêntico, byte a byte)
 * </pre>
 *
 * <p>Numa rodada com o teto de 18 ferramentas, usar o schema como ele vem custaria cerca de 2.100
 * caracteres a mais <b>em toda rodada</b> — e o custo de esquema por rodada é exatamente o que já
 * levou uma rodada a passar de seis minutos nesta máquina. Normalizar torna a migração para
 * {@code @Tool} gratuita em vez de uma regressão silenciosa de prompt.
 *
 * <p>O {@code $schema} sai porque declara o dialeto para um validador, e não há validador do outro
 * lado: quem lê é o modelo, que já recebe o dialeto implícito no formato. É texto que o usuário paga
 * em toda mensagem sem nada em troca.
 */
public final class ToolSchemaNormalizer {

    private static final String DIALECT_KEY = "$schema";

    private final ObjectMapper mapper;

    public ToolSchemaNormalizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Devolve o schema em forma compacta e sem o dialeto.
     *
     * <p>Entrada ilegível volta inalterada: normalizar é otimização de tamanho, e trocar um schema
     * grande por uma exceção seria pior — o modelo perderia a ferramenta inteira.
     */
    public String normalise(String schema) {
        if (schema == null || schema.isBlank()) {
            return schema;
        }
        try {
            JsonNode parsed = mapper.readTree(schema);
            if (!parsed.isObject()) {
                return schema;
            }
            ObjectNode object = (ObjectNode) parsed;
            object.remove(DIALECT_KEY);
            return object.toString();
        } catch (Exception exception) {
            return schema;
        }
    }
}
