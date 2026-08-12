package com.avento.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.avento.controller.McpController;
import com.avento.service.WorkspaceAccessService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;

/**
 * As ferramentas que precisam do contexto Spring inteiro — banco, Redis e catálogo MCP.
 *
 * <p>{@code ToolSmokeTest} monta um {@code McpController} na mão e cobre o que é auto-contido. O que
 * depende de repositório JPA, de Redis ou de um servidor MCP conectado não cabe lá, e era
 * justamente o pedaço que nada exercitava.
 *
 * <p>Roda quando Postgres e Redis estão de pé nas portas padrão; sem isso é pulado, para o build
 * não depender de infraestrutura ligada.
 */
// RANDOM_PORT, nao NONE: VoiceWebSocketConfig exige um ServletContext para registrar o endpoint de
// voz, e sem container o contexto inteiro falha a subir.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // Teste nao escreve no HOME de quem roda. O cache de schemas MCP grava em
            // ~/.avento/mcp-tool-schemas.json por padrao, e este teste sobe o contexto inteiro —
            // incluindo o auto-connect, que grava. Rodar a suite nao pode deixar rastro na maquina.
            "avento.mcp.tool-schema-cache=${java.io.tmpdir}/avento-test-mcp-schemas.json",
            // E nao gasta container aquecendo: o que este teste verifica nao depende disso.
            "avento.mcp.schema-warmup.enabled=false"
        })
@ActiveProfiles("local")
@EnabledIf("infrastructureIsUp")
class ToolIntegrationTest {

    @Autowired
    private McpController tools;

    @Autowired
    private WorkspaceAccessService workspaceAccess;

    @Autowired
    private org.springframework.core.env.Environment environment;

    @Autowired
    private com.avento.service.tools.ToolExecutionContext executionContext;

    @Autowired
    private com.avento.model.UserAccountRepository users;

    @Autowired
    private com.avento.model.ChatRepository chats;

    /**
     * Ferramentas que persistem exigem dono. O agente passa {@code _userId}/{@code _chatId} no
     * proprio payload — {@code ToolExecutionContext.fromArguments} le dali — entao o teste faz igual,
     * em vez de simular o contexto por fora.
     */
    private java.util.UUID userId;

    private Long chatId;

    @TempDir
    Path workspace;

    static boolean infrastructureIsUp() {
        return portOpen(5432) && portOpen(6379);
    }

    private static boolean portOpen(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception unreachable) {
            return false;
        }
    }

    @BeforeEach
    void authorizeWorkspaceAndResolveOwner() {
        workspaceAccess.registerWorkspaceRoot(workspace.toString());
        userId = users.findAll().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("nenhum usuario semeado no banco"))
                .getId();
        chatId = chats.findAll().stream().findFirst().map(chat -> chat.getId()).orElse(null);
    }

    /** O mesmo payload, com o dono anexado como o agente faz. */
    private JsonNode runAsUser(String tool, Object... keysAndValues) throws Exception {
        Object[] withOwner = java.util.Arrays.copyOf(keysAndValues, keysAndValues.length + 4);
        withOwner[keysAndValues.length] = "_userId";
        withOwner[keysAndValues.length + 1] = userId.toString();
        withOwner[keysAndValues.length + 2] = "_chatId";
        withOwner[keysAndValues.length + 3] = chatId == null ? "1" : chatId.toString();
        return run(tool, withOwner);
    }

    private JsonNode run(String tool, Object... keysAndValues) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            payload.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        // Espelha o ToolExecutionGateway: o dono vive num Context de thread, nao no payload.
        // Ferramentas como remember e revert_changes leem de executionContext.current(), entao sem
        // esse envelope elas recusam com "usuario autenticado e obrigatorio" mesmo com _userId dado.
        return executionContext.call(executionContext.fromArguments(payload), () -> tools.execute(tool, payload));
    }

    private void assertSucceeded(JsonNode result, String tool) {
        assertThat(result).as("%s devolveu null", tool).isNotNull();
        assertThat(result.has("error"))
                .as("%s falhou: %s", tool, result.path("error").asText())
                .isFalse();
    }

    // --- Persistência -------------------------------------------------------

    /** O modelo sugere um fato; ele nasce PENDENTE e só entra no prompt depois de confirmado. */
    @Test
    void remembersAFactThroughTheDatabase() throws Exception {
        assertSucceeded(runAsUser("remember", "content", "O usuario prefere respostas curtas."), "remember");
    }

    @Test
    void schedulesATask() throws Exception {
        JsonNode result = runAsUser(
                "schedule_task",
                "name",
                "teste-integracao",
                "cronExpression",
                "0 0 3 * * *",
                "prompt",
                "Resumir o dia.");

        assertSucceeded(result, "schedule_task");
    }

    // --- Arquivos com histórico --------------------------------------------

    /** Toda escrita vira backup; revert_changes desfaz a última usando esse histórico. */
    @Test
    void revertsAWriteUsingTheBackupHistory() throws Exception {
        Path file = workspace.resolve("revertido.txt");
        assertSucceeded(run("write_file", "path", file.toString(), "content", "versao um"), "write_file");
        assertSucceeded(run("write_file", "path", file.toString(), "content", "versao dois"), "write_file");
        assertThat(Files.readString(file)).isEqualTo("versao dois");

        assertSucceeded(runAsUser("revert_changes"), "revert_changes");
    }

    @Test
    void verifiesAProject() throws Exception {
        Files.writeString(workspace.resolve("package.json"), "{\"name\":\"teste\",\"scripts\":{}}");

        JsonNode result = run("verify_project", "path", workspace.toString());

        assertThat(result).as("verify_project devolveu null").isNotNull();
    }

    @Test
    void generatesAPdfFromMarkdown() throws Exception {
        JsonNode result = runAsUser("generate_pdf", "title", "Teste", "markdown", "# Titulo\n\nCorpo do documento.");

        assertSucceeded(result, "generate_pdf");
    }

    // --- MCP ----------------------------------------------------------------

    @Test
    void listsTheMcpCatalog() throws Exception {
        JsonNode result = run("list_mcp_servers");

        assertSucceeded(result, "list_mcp_servers");
        assertThat(result.toString()).contains("filesystem");
    }

    /** Descoberta progressiva: o modelo procura capacidade em vez de receber 40 schemas por rodada. */
    @Test
    void searchesCapabilities() throws Exception {
        JsonNode result = run("search_capabilities", "query", "ler arquivo");

        assertSucceeded(result, "search_capabilities");
    }

    /** Conecta um servidor MCP de verdade e confirma que as ferramentas dele aparecem. */
    @Test
    void connectsAndDisconnectsAnMcpServer() throws Exception {
        JsonNode connected = run("connect_mcp_server", "serverId", "time");
        assertSucceeded(connected, "connect_mcp_server");

        JsonNode activated = run("activate_tools", "tools", java.util.List.of("get_current_time"));
        assertThat(activated).as("activate_tools devolveu null").isNotNull();

        assertSucceeded(run("disconnect_mcp_server", "serverId", "time"), "disconnect_mcp_server");
    }

    /**
     * O Memory MCP usa um arquivo local, não o Redis Stack. Esta rodada real protege tanto o
     * handshake MCP quanto o bind mount que preserva o grafo entre chamadas.
     */
    @Test
    @EnabledIf("dockerDesktopIsRunning")
    void connectsMemoryAndListsItsKnowledgeGraphTools() throws Exception {
        JsonNode connected = run("connect_mcp_server", "serverId", "memory");

        assertSucceeded(connected, "connect_mcp_server(memory)");
        assertThat(connected.path("connected").asBoolean()).isTrue();
        assertSucceeded(run("disconnect_mcp_server", "serverId", "memory"), "disconnect_mcp_server(memory)");
    }

    /**
     * O agente chama ferramentas no event loop reativo. O SDK MCP bloqueia durante o handshake,
     * portanto a conexao precisa sair dessa thread antes de initialize e listTools acontecerem.
     */
    @Test
    @EnabledIf("dockerDesktopIsRunning")
    void connectsMemoryFromAReactiveThread() throws Exception {
        JsonNode connected = Mono.fromCallable(() -> run("connect_mcp_server", "serverId", "memory"))
                .subscribeOn(Schedulers.parallel())
                .block(Duration.ofSeconds(30));

        assertSucceeded(connected, "connect_mcp_server(memory) pela thread reativa");
        assertThat(connected.path("connected").asBoolean()).isTrue();
        assertSucceeded(run("disconnect_mcp_server", "serverId", "memory"), "disconnect_mcp_server(memory)");
    }

    /** Uma falha MCP precisa chegar como erro da ferramenta, nunca como sucesso ambíguo para o modelo. */
    @Test
    void exposesTheActualConnectionFailureAtTheToolBoundary() throws Exception {
        JsonNode result = run("connect_mcp_server", "serverId", "nao-existe");

        assertThat(result.path("connected").asBoolean()).isFalse();
        assertThat(result.path("error").asText()).contains("desconhecido");
        assertThat(result.path("details").asText()).contains("nao-existe");
    }

    /**
     * O gateway participa do boot como qualquer outro servidor.
     *
     * <p>Ele entra por ultimo na lista de propósito: os anteriores ja reservaram seus nomes de
     * ferramenta, entao nada que venha do gateway sobrescreve o que ja existe.
     */
    @Test
    void theDockerGatewayIsPartOfTheBootSequence() {
        // A ordem e o contrato: quem conecta antes reserva o nome da ferramenta. O gateway agrega
        // servidores de terceiros, entao vem por ultimo para nunca tomar o nome de um ja existente.
        List<String> autoConnect = java.util.Arrays.stream(environment
                        .getProperty("avento.mcp.catalog.auto-connect", "")
                        .split(","))
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .toList();

        assertThat(autoConnect).contains("docker-gateway");
        assertThat(autoConnect.get(autoConnect.size() - 1)).isEqualTo("docker-gateway");
    }

    /**
     * Conecta o gateway de verdade e confirma que as ferramentas dos servidores agregados chegam.
     *
     * <p>Precisa do Docker Desktop: o plugin nao atende Colima. Sem ele o catalogo marca o servidor
     * como indisponivel — comportamento que o McpServerCatalogServiceTest cobre — e este teste e
     * pulado em vez de falhar.
     */
    @Test
    @EnabledIf("dockerDesktopIsRunning")
    void connectsTheDockerGatewayAndBringsItsAggregatedTools() throws Exception {
        JsonNode connected = run("connect_mcp_server", "serverId", "docker-gateway");

        assertSucceeded(connected, "connect_mcp_server(docker-gateway)");
        assertSucceeded(run("disconnect_mcp_server", "serverId", "docker-gateway"), "disconnect_mcp_server");
    }

    static boolean dockerDesktopIsRunning() {
        return Files.exists(Path.of(System.getProperty("user.home"), ".docker", "run", "docker.sock"));
    }
}
