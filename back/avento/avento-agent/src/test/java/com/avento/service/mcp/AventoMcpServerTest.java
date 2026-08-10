package com.avento.service.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.service.tools.ToolProvider;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import tools.jackson.databind.ObjectMapper;

/**
 * O Avento passou a SERVIR MCP, não só a consumir. Estes testes travam as duas propriedades que
 * decidem se isso é seguro.
 */
class AventoMcpServerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolProvider tools = mock(ToolProvider.class);
    private final AventoMcpServer server = new AventoMcpServer(tools);

    private Set<String> exposedToolNames() {
        return java.util.Arrays.stream(AventoMcpServer.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(java.util.Objects::nonNull)
                .map(McpTool::name)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * <b>O teste mais importante deste arquivo.</b> O servidor MCP não tem autenticação própria, e o
     * portão que existe ({@code requireAuthorized}) limita QUAL pasta, não QUEM chama. Publicar
     * {@code terminal_run}, {@code write_file} ou {@code delete_file} numa porta HTTP é entregar
     * execução remota de código a quem alcançar a porta.
     *
     * <p>Se alguém acrescentar uma ferramenta de escrita aqui sem antes resolver autenticação, este
     * teste falha — e é para falhar.
     */
    @Test
    void exposesOnlyReadOnlyTools() {
        Set<String> proibidas = Set.of(
                "terminal_run",
                "terminal_start",
                "write_file",
                "edit_file",
                "delete_file",
                "delete_directory",
                "create_directory",
                "run_shortcut",
                "open_app",
                "create_vite_project");

        for (String exposed : exposedToolNames()) {
            String underlying = exposed.replaceFirst("^avento_", "");
            assertThat(proibidas)
                    .as("ferramenta de escrita/execucao exposta por MCP sem autenticacao: " + exposed)
                    .doesNotContain(underlying);
        }
    }

    /** Todo nome publicado carrega o prefixo do servidor: outro agente precisa saber de onde veio. */
    @Test
    void everyExposedToolIsNamespaced() {
        assertThat(exposedToolNames())
                .isNotEmpty()
                .allSatisfy(name -> assertThat(name).startsWith("avento_"));
    }

    /**
     * Delega para o MESMO {@link ToolProvider} do agente interno, com o nome interno da ferramenta.
     *
     * <p>Reimplementar a leitura aqui criaria uma segunda porta para o workspace — e a segunda porta
     * é a que esquece de chamar {@code requireAuthorized}.
     */
    @Test
    void delegatesToTheSameToolProviderTheInternalAgentUses() throws Exception {
        when(tools.execute(eq("read_file"), any()))
                .thenReturn(mapper.createObjectNode().put("content", "conteudo"));

        String result = server.readFile("/tmp/projeto/pom.xml");

        assertThat(result).contains("conteudo");
    }

    /** Parâmetro opcional ausente não vai no payload — quem decide o padrão é a ferramenta. */
    @Test
    void omitsTheOptionalDepthWhenItIsNotGiven() throws Exception {
        when(tools.execute(eq("directory_tree"), any())).thenReturn(mapper.createObjectNode());

        server.directoryTree("/tmp/projeto", null);

        org.mockito.ArgumentCaptor<Map<String, Object>> payload = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(tools).execute(eq("directory_tree"), payload.capture());
        assertThat(payload.getValue()).containsKey("path").doesNotContainKey("maxDepth");
    }

    /**
     * Falha volta como JSON legível, não como exceção.
     *
     * <p>Do outro lado há um agente, não uma pessoa: "pasta não autorizada" é acionável para ele,
     * um stack trace não é — e um erro que atravessa o transporte derruba a sessão MCP inteira.
     */
    @Test
    void turnsAFailureIntoAReadableAnswerInsteadOfBreakingTheSession() throws Exception {
        when(tools.execute(any(), any())).thenThrow(new IllegalStateException("pasta nao autorizada"));

        String result = server.readFile("/etc/passwd");

        assertThat(result).contains("error").contains("pasta nao autorizada");
    }

    /** As descrições vão para o modelo do outro agente: sem elas ele não sabe quando chamar. */
    @Test
    void everyExposedToolCarriesADescription() {
        List<McpTool> annotations = java.util.Arrays.stream(AventoMcpServer.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(java.util.Objects::nonNull)
                .toList();

        assertThat(annotations).isNotEmpty();
        for (McpTool annotation : annotations) {
            assertThat(annotation.description()).isNotBlank();
        }
    }

    /** Guarda de sanidade da reflexão acima: se o nome do método mudar, o teste tem de perceber. */
    @Test
    void readFileIsAmongTheExposedTools() throws Exception {
        Method method = AventoMcpServer.class.getMethod("readFile", String.class);

        assertThat(method.getAnnotation(McpTool.class)).isNotNull();
        assertThat(method.getAnnotation(McpTool.class).name()).isEqualTo("avento_read_file");
    }
}
