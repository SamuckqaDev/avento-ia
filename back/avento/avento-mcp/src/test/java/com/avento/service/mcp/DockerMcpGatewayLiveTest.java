package com.avento.service.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.avento.service.dto.ConnectionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * O Avento conectando no Docker MCP Gateway de verdade.
 *
 * <p>O teste de integração ao lado usa o servidor {@code docker} e espera ver os containers do
 * projeto; este usa {@code fetch}, que não depende de nada estar rodando e vem pronto no catálogo do
 * Toolkit. O ponto aqui não é a ferramenta e sim o transporte: um único processo servindo
 * ferramentas de servidores que rodam em containers isolados, em vez de um processo npx solto por
 * servidor.
 *
 * <p>Roda sozinho quando o Docker Desktop está no ar — a mesma condição que o catálogo verifica —
 * e é pulado quando não está. A primeira execução baixa a imagem, então o tempo limite é generoso.
 */
class DockerMcpGatewayLiveTest {

    private final McpClientManager manager = new McpClientManager(new ObjectMapper(), Duration.ofSeconds(120));

    static boolean dockerDesktopRunning() {
        return Files.exists(Path.of(System.getProperty("user.home"), ".docker", "run", "docker.sock"));
    }

    @AfterEach
    void closeGateway() {
        manager.closeAll();
    }

    @Test
    @EnabledIf("dockerDesktopRunning")
    void connectsThroughTheGatewayAndSeesTheAggregatedServerTools() {
        ConnectionResult connection = manager.connect(
                "docker-gateway", List.of("docker", "mcp", "gateway", "run", "--servers", "fetch"), Map.of(), Set.of());

        assertThat(connection.connected())
                .as("o gateway tem de conectar: %s", connection.error())
                .isTrue();
        assertThat(connection.tools())
                .as("o gateway tem de expor as ferramentas do servidor agregado")
                .isNotEmpty();
        assertThat(connection.tools().stream().map(tool -> tool.originalName()).toList())
                .contains("fetch");
    }
}
