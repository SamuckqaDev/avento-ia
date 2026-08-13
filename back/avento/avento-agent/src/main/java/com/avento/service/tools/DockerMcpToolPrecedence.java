package com.avento.service.tools;

import com.avento.dto.ToolDefinition;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Prefere uma ferramenta anunciada pelo Docker MCP quando ela declara a mesma capacidade nativa.
 *
 * <p>A equivalência é estrita: o servidor precisa ser {@code docker-gateway} e o
 * {@code originalName} precisa ser exatamente o nome de uma ferramenta nativa. Similaridade de
 * descrição não basta para trocar uma operação do host por uma operação em container, pois os
 * parâmetros, o escopo de workspace e o efeito no macOS podem ser diferentes.
 */
public final class DockerMcpToolPrecedence {

    public static final String DOCKER_GATEWAY_SERVER_ID = "docker-gateway";

    private DockerMcpToolPrecedence() {}

    /** Ferramentas nativas substituídas por uma conexão real do gateway nesta sessão. */
    public static Map<String, ToolDefinition> nativeReplacements(List<ToolDefinition> connectedTools) {
        Map<String, ToolDefinition> replacements = new LinkedHashMap<>();
        (connectedTools == null ? List.<ToolDefinition>of() : connectedTools).stream()
                .filter(DockerMcpToolPrecedence::isNativeEquivalent)
                .sorted(Comparator.comparing(ToolDefinition::exposedName))
                .forEach(tool -> replacements.putIfAbsent(tool.originalName(), tool));
        return Map.copyOf(replacements);
    }

    /** Nome canônico que aparece para o modelo, quando a ferramenta substitui uma nativa. */
    public static Optional<String> canonicalName(ToolDefinition tool) {
        return isNativeEquivalent(tool) ? Optional.of(tool.originalName()) : Optional.empty();
    }

    /**
     * Ordena as ferramentas para que o gateway escolhido para substituir uma nativa seja o
     * primeiro representante daquele nome canônico no catálogo do modelo.
     */
    public static List<ToolDefinition> prioritize(List<ToolDefinition> connectedTools) {
        List<ToolDefinition> tools = connectedTools == null ? List.of() : connectedTools;
        Map<String, ToolDefinition> replacements = nativeReplacements(tools);
        return tools.stream()
                .sorted(Comparator.comparing(
                                (ToolDefinition tool) -> !replacements.containsValue(tool))
                        .thenComparing(ToolDefinition::exposedName))
                .toList();
    }

    private static boolean isNativeEquivalent(ToolDefinition tool) {
        return tool != null
                && DOCKER_GATEWAY_SERVER_ID.equals(tool.serverName())
                && tool.originalName() != null
                && LocalToolNames.ALL.contains(tool.originalName());
    }
}
