package com.avento.service.mcp;

import com.avento.service.tools.ToolProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Expõe o Avento como SERVIDOR MCP: as ferramentas dele passam a ser chamáveis por outros agentes.
 *
 * <p>Até aqui o Avento só consumia MCP. Servir é o outro lado do protocolo, e é o que transforma o
 * agente numa peça reutilizável em vez de um sistema fechado.
 *
 * <h2>Por que só ferramentas de LEITURA</h2>
 *
 * As 57 ferramentas locais incluem {@code terminal_run}, {@code write_file} e {@code delete_file}.
 * Publicá-las numa porta HTTP é entregar execução remota de código a qualquer um que alcance a
 * porta — o servidor MCP não tem autenticação própria, e o portão que existe
 * ({@code WorkspaceAccessService.requireAuthorized}) limita QUAL pasta, não QUEM chama.
 *
 * <p>Por isso este servidor publica um subconjunto de leitura. Ampliar exige antes resolver
 * autenticação, e essa é uma decisão de projeto, não um detalhe de anotação.
 *
 * <h2>Por que delega em vez de reimplementar</h2>
 *
 * Cada método aqui monta o payload e chama {@link ToolProvider#execute}, que é o mesmo caminho do
 * agente interno. Reimplementar a leitura de arquivo aqui criaria uma segunda porta para o
 * workspace — e a segunda porta é a que esquece de chamar {@code requireAuthorized}.
 *
 * <h2>Desligado por padrão</h2>
 *
 * {@code avento.mcp.server.enabled} nasce {@code false}. Abrir uma porta que serve conteúdo do
 * workspace tem de ser escolha explícita de quem roda, nunca padrão de quem instala.
 */
@Service
@ConditionalOnProperty(name = "avento.mcp.server.enabled", havingValue = "true")
public class AventoMcpServer {

    private final ToolProvider tools;

    public AventoMcpServer(ToolProvider tools) {
        this.tools = tools;
    }

    @McpTool(
            name = "avento_read_file",
            description = "Lê um arquivo de texto de uma pasta de projeto autorizada e devolve o conteúdo.")
    public String readFile(
            @McpToolParam(description = "Caminho absoluto do arquivo dentro de um workspace autorizado") String path) {
        return call("read_file", Map.of("path", path));
    }

    @McpTool(
            name = "avento_directory_tree",
            description = "Lista a árvore de arquivos de uma pasta de projeto autorizada, até uma profundidade.")
    public String directoryTree(
            @McpToolParam(description = "Caminho absoluto da pasta") String path,
            @McpToolParam(description = "Profundidade máxima da árvore; opcional") Integer maxDepth) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("path", path);
        if (maxDepth != null) {
            payload.put("maxDepth", maxDepth);
        }
        return call("directory_tree", payload);
    }

    @McpTool(
            name = "avento_search_files",
            description = "Procura arquivos por nome dentro de uma pasta de projeto autorizada.")
    public String searchFiles(
            @McpToolParam(description = "Caminho absoluto da pasta onde procurar") String path,
            @McpToolParam(description = "Trecho do nome do arquivo") String query) {
        return call("search_files", Map.of("path", path, "query", query));
    }

    @McpTool(
            name = "avento_search_code",
            description = "Procura no código de um projeto autorizado. Usa busca vetorial quando o índice está pronto e"
                    + " busca literal enquanto não está; a resposta diz qual dos dois respondeu.")
    public String searchCode(
            @McpToolParam(description = "Caminho absoluto da pasta do projeto") String path,
            @McpToolParam(description = "O que procurar, em linguagem natural ou trecho literal") String query) {
        return call("search_code", Map.of("path", path, "query", query));
    }

    /**
     * Caminho único de execução: mesmo {@link ToolProvider} do agente interno, mesmas guardas.
     *
     * <p>A falha volta como texto em vez de exceção porque o cliente MCP é outro agente, e um erro
     * legível ("pasta não autorizada") é acionável para ele; um stack trace não é.
     */
    private String call(String toolName, Map<String, Object> arguments) {
        try {
            JsonNode result = tools.execute(toolName, arguments);
            return result == null ? "" : result.toString();
        } catch (Exception exception) {
            return "{\"error\":\"" + exception.getMessage() + "\"}";
        }
    }
}
