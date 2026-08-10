package com.avento.dto;

/**
 * Uma ferramenta que pode ser escolhida ao montar um agente.
 *
 * <p>Diferente do que {@code GET /api/mcp/tools} devolve: aquilo é o que está conectado AGORA; isto
 * é o que a pessoa pode escolher, inclusive de servidor em container que está desligado. Quem monta
 * um agente escolhe capacidades, não estado de conexão.
 *
 * @param name nome da ferramenta como o modelo a chamará
 * @param source de onde vem: {@code LOCAL} (código do Avento), {@code CONTAINER} (imagem {@code
 *     mcp/*}) ou {@code MCP_SERVER} (servidor externo conectado)
 * @param serverId servidor de origem quando não for local; vazio para {@code LOCAL}
 * @param category agrupamento para a tela; vazio quando a origem não classifica
 * @param riskLevel risco declarado da ferramenta local; vazio para as de fora, que o Avento não
 *     classifica — e dizer "baixo" sobre o que não se sabe seria pior que dizer nada
 * @param description o que ela faz, na linguagem que o modelo lê
 * @param requiresConnection {@code true} quando escolher implica conectar um servidor antes de usar
 */
public record SelectableTool(
        String name,
        String source,
        String serverId,
        String category,
        String riskLevel,
        String description,
        boolean requiresConnection) {

    public static final String SOURCE_LOCAL = "LOCAL";
    public static final String SOURCE_CONTAINER = "CONTAINER";
    public static final String SOURCE_MCP_SERVER = "MCP_SERVER";
}
