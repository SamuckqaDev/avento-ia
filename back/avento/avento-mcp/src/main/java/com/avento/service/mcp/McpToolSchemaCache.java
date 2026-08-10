package com.avento.service.mcp;

import com.avento.dto.ToolDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Guarda o {@code tools/list} de cada imagem MCP, chaveado pelo DIGEST dela.
 *
 * <p><b>Para que serve:</b> hoje o Avento só sabe quais ferramentas um servidor MCP oferece com o
 * servidor de pé. Isso é aceitável quando o servidor é um {@code npx} que já está rodando, mas
 * quebra o caso que interessa: montar a lista de ferramentas na tela de criação de agente, ou
 * resolver o {@code allowed_tools} de um perfil, exigiria subir todos os containers só para
 * perguntar o que eles têm. Com o cache, o catálogo se monta a partir do disco e o container sobe
 * apenas quando uma ferramenta é de fato chamada.
 *
 * <p><b>Por que o digest e não a tag:</b> a tag é um ponteiro móvel. {@code mcp/fetch:latest} hoje e
 * amanhã podem ser imagens diferentes, e um cache chaveado por tag serviria schema velho para
 * imagem nova — sem erro, porque o modelo simplesmente chamaria uma ferramenta que não existe mais.
 * O digest é o conteúdo: se ele é o mesmo, o {@code tools/list} é o mesmo, e isso vale para sempre.
 * Foi por isso que as sete imagens sem tag desta máquina puderam ser reancoradas sem risco — o
 * digest não mudou.
 *
 * <p><b>O que este cache NÃO faz:</b> não decide se uma ferramenta pode ser usada (isso é o
 * {@code AgentPermissionService}) e não substitui a conexão — schema em cache é uma promessa sobre
 * a forma da ferramenta, não sobre o servidor estar disponível agora.
 */
@Service
public class McpToolSchemaCache {

    private static final Logger logger = LoggerFactory.getLogger(McpToolSchemaCache.class);

    /** Resolve uma referência de imagem para o digest do conteúdo. Injetável para teste. */
    @FunctionalInterface
    public interface DigestResolver {
        Optional<String> digestOf(String image);
    }

    private final ObjectMapper mapper;
    private final Path file;
    private final DigestResolver digestResolver;
    private final Map<String, List<ToolDefinition>> byDigest = new ConcurrentHashMap<>();

    // Sem @Autowired explicito o Spring ve duas construtoras, nao escolhe nenhuma e procura uma sem
    // argumentos — que nao existe. A de pacote so serve ao teste.
    @org.springframework.beans.factory.annotation.Autowired
    public McpToolSchemaCache(
            ObjectMapper mapper,
            @Value("${avento.mcp.tool-schema-cache:}") String configuredPath,
            @Value("${avento.mcp.tool-schema-cache-timeout:10s}") java.time.Duration inspectTimeout) {
        this(mapper, resolvePath(configuredPath), new DockerDigestResolver(inspectTimeout));
    }

    McpToolSchemaCache(ObjectMapper mapper, Path file, DigestResolver digestResolver) {
        this.mapper = mapper;
        this.file = file;
        this.digestResolver = digestResolver;
        load();
    }

    private static Path resolvePath(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".avento", "mcp-tool-schemas.json");
    }

    /**
     * Ferramentas conhecidas da imagem, sem subir nada.
     *
     * <p>Devolve vazio tanto para "imagem desconhecida" quanto para "digest não resolvido" — o
     * chamador trata os dois igual: não sei, então conecte para descobrir.
     */
    public List<ToolDefinition> tools(String image) {
        return digestResolver
                .digestOf(image)
                .map(digest -> byDigest.getOrDefault(digest, List.of()))
                .orElse(List.of());
    }

    /** Grava o que uma conexão bem-sucedida descobriu. Sem digest, não grava — chave errada é pior que ausente. */
    public void record(String image, List<ToolDefinition> tools) {
        if (image == null || image.isBlank() || tools == null || tools.isEmpty()) {
            return;
        }
        Optional<String> digest = digestResolver.digestOf(image);
        if (digest.isEmpty()) {
            logger.debug("Sem digest para {}; schema nao sera cacheado.", image);
            return;
        }
        byDigest.put(digest.get(), List.copyOf(tools));
        save();
    }

    /** Entradas em cache, para diagnóstico. */
    public int size() {
        return byDigest.size();
    }

    private void load() {
        if (!Files.isReadable(file)) {
            return;
        }
        try {
            Map<String, List<ToolDefinition>> stored =
                    mapper.readValue(Files.readAllBytes(file), new TypeReference<>() {});
            byDigest.putAll(stored);
            logger.debug("Cache de schemas MCP carregado: {} imagens.", byDigest.size());
        } catch (Exception exception) {
            // Cache corrompido nao pode derrubar a aplicacao: ele e otimizacao, nao fonte de
            // verdade. Comeca vazio e se reconstroi na primeira conexao de cada servidor.
            logger.warn("Cache de schemas MCP ilegivel em {}; comecando vazio: {}", file, exception.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Map<String, List<ToolDefinition>> snapshot = new LinkedHashMap<>(byDigest);
            Files.write(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot));
        } catch (IOException exception) {
            logger.warn("Nao foi possivel gravar o cache de schemas MCP em {}: {}", file, exception.getMessage());
        }
    }

    /**
     * Resolve o digest com {@code docker image inspect}.
     *
     * <p>Custa um processo, então é chamado na conexão e na montagem do catálogo de ferramentas —
     * nunca por rodada do agente. É muito mais barato que subir o container, que é a alternativa.
     */
    static final class DockerDigestResolver implements DigestResolver {

        private final java.time.Duration timeout;

        DockerDigestResolver(java.time.Duration timeout) {
            this.timeout = timeout;
        }

        @Override
        public Optional<String> digestOf(String image) {
            if (image == null || image.isBlank()) {
                return Optional.empty();
            }
            Process process = null;
            try {
                process = new ProcessBuilder("docker", "image", "inspect", "--format", "{{.Id}}", image)
                        .redirectErrorStream(false)
                        .start();
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    return Optional.empty();
                }
                if (process.exitValue() != 0) {
                    return Optional.empty();
                }
                String output = new String(process.getInputStream().readAllBytes()).trim();
                return output.startsWith("sha256:") ? Optional.of(output) : Optional.empty();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (Exception exception) {
                return Optional.empty();
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }
    }
}
