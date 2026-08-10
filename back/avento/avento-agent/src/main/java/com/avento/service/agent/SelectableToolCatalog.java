package com.avento.service.agent;

import com.avento.dto.SelectableTool;
import com.avento.service.mcp.McpServerCatalogService;
import com.avento.service.tools.ToolCapabilityRegistry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * O que uma pessoa pode escolher ao montar o agente dela.
 *
 * <p><b>Por que não serve o {@code GET /api/mcp/tools}:</b> aquele endpoint lista o que está
 * conectado AGORA. Quem monta um agente escolhe capacidades, não estado de conexão — e o servidor
 * de uma ferramenta escolhida sobe quando ela for usada, não quando ela for escolhida.
 *
 * <p><b>Três origens, e a tela precisa distinguir:</b>
 *
 * <ul>
 *   <li>{@code LOCAL} — as ferramentas do próprio Avento. Sempre disponíveis, com categoria e risco
 *       declarados.
 *   <li>{@code CONTAINER} — imagens {@code mcp/*}. Vêm do cache por digest, então listar NÃO sobe
 *       container nenhum; escolher implica conectar depois.
 *   <li>{@code MCP_SERVER} — servidores externos já conectados.
 * </ul>
 *
 * <p><b>O que este catálogo NÃO faz:</b> não diz se a pessoa PODE usar a ferramenta. Escolher é
 * intenção; permissão continua sendo do {@code AgentPermissionService}, e uma ferramenta escolhida e
 * arriscada continua passando pela aprovação. Confundir os dois transformaria a tela de montagem num
 * portão de segurança, que é o que ela não é.
 *
 * <p><b>Risco em branco é honestidade:</b> as ferramentas de fora não são classificadas pelo Avento.
 * Dizer "baixo" sobre o que não se sabe seria pior que não dizer nada.
 */
@Service
public class SelectableToolCatalog {

    private final ToolCapabilityRegistry localTools;
    private final ObjectProvider<McpServerCatalogService> mcpCatalogProvider;

    public SelectableToolCatalog(
            ToolCapabilityRegistry localTools, ObjectProvider<McpServerCatalogService> mcpCatalogProvider) {
        this.localTools = localTools;
        this.mcpCatalogProvider = mcpCatalogProvider;
    }

    /**
     * Tudo que é escolhível, em ordem estável: locais primeiro, depois containers, depois servidores
     * conectados.
     *
     * <p>A ordem é estável de propósito — uma lista que embaralha entre chamadas faz a tela pular sob
     * o cursor de quem está escolhendo.
     */
    public List<SelectableTool> all() {
        List<SelectableTool> tools = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        localTools.all().stream()
                .sorted(java.util.Comparator.comparing(capability -> capability.name()))
                .forEach(capability -> {
                    if (seen.add(capability.name())) {
                        tools.add(new SelectableTool(
                                capability.name(),
                                SelectableTool.SOURCE_LOCAL,
                                "",
                                capability.category() == null
                                        ? ""
                                        : capability.category().name(),
                                capability.riskLevel() == null
                                        ? ""
                                        : capability.riskLevel().name(),
                                capability.summary() == null ? "" : capability.summary(),
                                false));
                    }
                });

        McpServerCatalogService mcpCatalog = mcpCatalogProvider.getIfAvailable();
        if (mcpCatalog == null) {
            return List.copyOf(tools);
        }

        for (var descriptor : mcpCatalog.catalog(List.of())) {
            // Do cache por digest: responde sem subir container. Sem isto, montar a tela custaria
            // iniciar todas as imagens so para perguntar o que elas tem.
            for (var tool : mcpCatalog.knownTools(descriptor.id())) {
                if (seen.add(tool.exposedName())) {
                    tools.add(new SelectableTool(
                            tool.exposedName(),
                            SelectableTool.SOURCE_CONTAINER,
                            descriptor.id(),
                            "",
                            "",
                            tool.description() == null ? "" : tool.description(),
                            !descriptor.connected()));
                }
            }
        }

        return List.copyOf(tools);
    }
}
