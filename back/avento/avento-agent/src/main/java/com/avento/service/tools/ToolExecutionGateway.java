package com.avento.service.tools;

import com.avento.service.dto.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import java.util.List;

/** Stable boundary used by the agent regardless of whether a tool is local or MCP-backed. */
@Service
public class ToolExecutionGateway {

    private final ToolProvider toolProvider;
    private final ToolResultVerifier resultVerifier;
    private final ToolExecutionContext executionContext;

    // Auto-connect sob demanda de servidores MCP relevantes ao pedido. Injeção por campo opcional
    // para não ampliar o construtor nem quebrar os testes que instanciam o gateway diretamente.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.avento.service.mcp.McpAutoConnectService autoConnectService;

    public ToolExecutionGateway(
            ToolProvider toolProvider, ToolResultVerifier resultVerifier, ToolExecutionContext executionContext) {
        this.toolProvider = toolProvider;
        this.resultVerifier = resultVerifier;
        this.executionContext = executionContext;
    }

    public ArrayNode listTools() {
        return toolProvider.listTools();
    }

    public ArrayNode listTools(UUID userId, Long chatId, String runId) {
        try {
            return executionContext.call(new Context(userId, chatId, runId), toolProvider::listTools);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not list tools for the current chat", exception);
        }
    }

    /** Tool catalog for a round plus the MCP servers auto-connected for this specific request. */
    public record ToolListing(ArrayNode tools, List<String> autoConnectedServers) {}

    /**
     * Lista as ferramentas depois de conectar sob demanda os servidores MCP relevantes ao pedido —
     * assim as ferramentas de um servidor que o pedido precisa (ex.: git) aparecem já nesta rodada,
     * sem depender de o modelo lembrar de conectar. O auto-connect roda dentro do contexto de
     * execução (escopo do chat), como a listagem.
     */
    public ArrayNode listTools(
            UUID userId, Long chatId, String runId, String requestText, List<String> workspaceRoots) {
        return listToolsWithAutoConnect(userId, chatId, runId, requestText, workspaceRoots)
                .tools();
    }

    /**
     * Variante que também informa QUAIS servidores foram conectados para este pedido, para que o
     * chamador garanta a exposição das ferramentas deles na mesma rodada. Conectar e esconder era
     * o comportamento anterior: o auto-connect ligava o servidor (ex.: fetch para "cotação do
     * dólar"), mas o filtro de intenção não conhecia essas palavras e escondia as ferramentas.
     */
    public ToolListing listToolsWithAutoConnect(
            UUID userId, Long chatId, String runId, String requestText, List<String> workspaceRoots) {
        try {
            return executionContext.call(new Context(userId, chatId, runId), () -> {
                List<String> connected = autoConnectService != null
                        ? autoConnectService.connectRelevant(requestText, workspaceRoots)
                        : List.of();
                return new ToolListing(toolProvider.listTools(), connected);
            });
        } catch (Exception exception) {
            throw new IllegalStateException("Could not list tools for the current chat", exception);
        }
    }

    public JsonNode execute(String toolName, Map<String, Object> arguments) throws Exception {
        return executionContext.call(executionContext.fromArguments(arguments), () -> {
            JsonNode result = toolProvider.execute(toolName, arguments);
            return resultVerifier.verify(toolName, arguments, result);
        });
    }
}
