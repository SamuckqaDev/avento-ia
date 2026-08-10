package com.avento.service.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.avento.dto.ConnectionResult;
import com.avento.dto.ToolDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import tools.jackson.databind.ObjectMapper;

/**
 * O Avento falando MCP com um CONTAINER, pelo caminho que de fato funciona.
 *
 * <p>O {@code DockerMcpGatewayLiveTest} ao lado usa o gateway do Docker, que está quebrado nesta
 * máquina — medido: o catálogo de 101 servidores não contém nenhum {@code mcp/*} e o
 * {@code registry.yaml} lista os oito com {@code ref: ""}. Este teste usa o caminho que substituiu
 * aquele: {@code docker run --rm -i mcp/<nome>}, sem gateway e sem catálogo.
 *
 * <p><b>Por que ele precisava existir agora:</b> a migração para o Spring Boot 4 trocou a biblioteca
 * JSON do transporte MCP — {@code mcp-json-jackson2} virou {@code mcp-json-jackson3}, e o
 * {@code JacksonMcpJsonMapper} passou a receber um {@code JsonMapper} dedicado. Nenhum teste
 * exercitava esse caminho: os unitários usam mock, e o ciclo real só tinha sido provado à mão, antes
 * da migração. Trocar a biblioteca de serialização de um protocolo e não reexercitar o protocolo é
 * como o defeito de enquadramento entra sem ninguém ver.
 *
 * <p>Usa {@code mcp/time} porque é a menor das imagens e não depende de rede para responder.
 */
class McpContainerTransportLiveTest {

    private static final String IMAGE = "mcp/time";

    private final McpClientManager manager = new McpClientManager(new ObjectMapper(), Duration.ofSeconds(120));

    /**
     * Só roda com Docker Desktop no ar E a imagem já no disco.
     *
     * <p>O {@code --pull=never} do lançamento real garante que a ausência da imagem caia no fallback
     * em vez de pendurar esperando a rede; aqui a mesma ausência simplesmente pula o teste, para não
     * transformar uma máquina limpa em build vermelho.
     */
    static boolean dockerReadyWithImage() {
        boolean desktopUp = Files.exists(Path.of(System.getProperty("user.home"), ".docker", "run", "docker.sock"));
        if (!desktopUp) {
            return false;
        }
        try {
            Process process = new ProcessBuilder("docker", "image", "inspect", "--format", "{{.Id}}", IMAGE)
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @AfterEach
    void closeEverything() {
        manager.closeAll();
    }

    /**
     * O ciclo inteiro: sobe o container, faz {@code initialize}, lê {@code tools/list} e executa uma
     * ferramenta de verdade — tudo através do {@link McpClientManager}, que é o caminho da aplicação.
     */
    @Test
    @EnabledIf("dockerReadyWithImage")
    void talksTheWholeProtocolToAContainerAndGetsARealAnswer() {
        ConnectionResult connection = manager.connect(
                "time-container", List.of("docker", "run", "--rm", "-i", "--pull=never", IMAGE), Map.of(), Set.of());

        assertThat(connection.connected())
                .as("conexao falhou: " + connection.error())
                .isTrue();

        List<String> toolNames =
                connection.tools().stream().map(ToolDefinition::originalName).toList();
        assertThat(toolNames).contains("get_current_time");

        Object result = manager.callTool(
                connection.tools().stream()
                        .filter(tool -> "get_current_time".equals(tool.originalName()))
                        .map(ToolDefinition::exposedName)
                        .findFirst()
                        .orElseThrow(),
                Map.of("timezone", "UTC"));

        assertThat(String.valueOf(result))
                .as("a ferramenta tem de devolver conteudo real, nao so um envelope vazio")
                .contains("UTC");
    }
}
