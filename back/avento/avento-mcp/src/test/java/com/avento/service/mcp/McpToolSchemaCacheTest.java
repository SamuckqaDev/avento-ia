package com.avento.service.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.avento.service.dto.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O cache existe para responder "quais ferramentas esta imagem tem?" sem subir o container.
 *
 * <p>O resolvedor de digest é injetado aqui de propósito: um teste que dependesse do {@code docker}
 * instalado não roda em CI nem em máquina limpa, e o que precisa ser garantido é a REGRA de
 * chaveamento, não o `docker image inspect`.
 */
class McpToolSchemaCacheTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ToolDefinition tool(String name) {
        return new ToolDefinition(name, name, "fetch", "descricao de " + name, Map.of("type", "object"));
    }

    private McpToolSchemaCache cacheWith(Path file, Map<String, String> digests) {
        return new McpToolSchemaCache(
                mapper, file, image -> Optional.ofNullable(digests.get(image)));
    }

    @Test
    void answersWithTheToolsRecordedForTheImage(@TempDir Path directory) {
        McpToolSchemaCache cache = cacheWith(directory.resolve("c.json"), Map.of("mcp/fetch", "sha256:aaa"));

        cache.record("mcp/fetch", List.of(tool("fetch")));

        assertThat(cache.tools("mcp/fetch")).extracting(ToolDefinition::exposedName).containsExactly("fetch");
    }

    @Test
    void answersEmptyForAnImageItHasNeverSeen(@TempDir Path directory) {
        McpToolSchemaCache cache = cacheWith(directory.resolve("c.json"), Map.of("mcp/time", "sha256:bbb"));

        assertThat(cache.tools("mcp/time")).isEmpty();
    }

    /**
     * A tag é um ponteiro móvel: {@code mcp/fetch} hoje e amanhã podem ser imagens diferentes. Se o
     * cache fosse chaveado por tag, ele serviria schema velho para imagem nova — e o modelo chamaria
     * uma ferramenta que não existe mais, sem erro nenhum no caminho.
     */
    @Test
    void aNewDigestUnderTheSameTagIsAMiss(@TempDir Path directory) {
        Path file = directory.resolve("c.json");
        McpToolSchemaCache antes = cacheWith(file, Map.of("mcp/fetch", "sha256:versao-antiga"));
        antes.record("mcp/fetch", List.of(tool("fetch")));

        // Mesma tag, conteudo novo: o digest muda e o cache tem de admitir que nao sabe.
        McpToolSchemaCache depois = cacheWith(file, Map.of("mcp/fetch", "sha256:versao-nova"));

        assertThat(depois.tools("mcp/fetch")).isEmpty();
    }

    /** Sem digest não há chave confiável, e chave errada é pior que ausência de chave. */
    @Test
    void doesNotRecordWhenTheDigestCannotBeResolved(@TempDir Path directory) {
        McpToolSchemaCache cache = cacheWith(directory.resolve("c.json"), Map.of());

        cache.record("mcp/fetch", List.of(tool("fetch")));

        assertThat(cache.size()).isZero();
    }

    @Test
    void survivesARestartByReadingTheFileBack(@TempDir Path directory) {
        Path file = directory.resolve("schemas.json");
        Map<String, String> digests = Map.of("mcp/memory", "sha256:ccc");

        cacheWith(file, digests).record("mcp/memory", List.of(tool("read_graph"), tool("open_nodes")));

        McpToolSchemaCache reaberto = cacheWith(file, digests);

        assertThat(reaberto.tools("mcp/memory"))
                .extracting(ToolDefinition::exposedName)
                .containsExactly("read_graph", "open_nodes");
    }

    /**
     * Cache corrompido é otimização perdida, não aplicação derrubada. Ele se reconstrói na primeira
     * conexão de cada servidor.
     */
    @Test
    void startsEmptyInsteadOfFailingWhenTheFileIsCorrupt(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("schemas.json");
        Files.writeString(file, "{ isto nao e json ");

        McpToolSchemaCache cache = cacheWith(file, Map.of("mcp/fetch", "sha256:aaa"));

        assertThat(cache.size()).isZero();
        assertThat(cache.tools("mcp/fetch")).isEmpty();
    }

    @Test
    void ignoresAnEmptyToolList(@TempDir Path directory) {
        McpToolSchemaCache cache = cacheWith(directory.resolve("c.json"), Map.of("mcp/fetch", "sha256:aaa"));

        cache.record("mcp/fetch", List.of());

        assertThat(cache.size()).isZero();
    }
}
