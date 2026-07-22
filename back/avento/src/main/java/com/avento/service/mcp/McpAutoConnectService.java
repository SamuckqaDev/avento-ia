package com.avento.service.mcp;

import com.avento.service.dto.ConnectionResult;
import com.avento.service.dto.ServerDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * "Pega a ferramenta que precisa": olha o catálogo de servidores MCP, vê o que o pedido do usuário
 * precisa e conecta sob demanda os servidores relevantes que estão DISPONÍVEIS mas ainda não
 * conectados — para que as ferramentas deles apareçam pro modelo nesta rodada. Determinístico, não
 * depende de o modelo lembrar de chamar {@code connect_mcp_server}.
 *
 * <p>Os gatilhos (id do servidor -> palavras-chave) vivem em {@code agent/mcp-auto-connect.md},
 * organizados por categoria — não chumbados no código. O casamento é por palavra inteira, sem acento.
 *
 * <p>Só conecta servidores {@code available} (launchable): se o binário não existe/servidor está
 * quebrado, ele nem tenta — evitando pendurar a rodada num timeout.
 */
@Service
public class McpAutoConnectService {

    private static final Logger logger = LoggerFactory.getLogger(McpAutoConnectService.class);
    private static final String KEYWORDS_RESOURCE = "agent/mcp-auto-connect.md";

    private final McpServerCatalogService catalogService;
    private final Map<String, List<Pattern>> patternsByServer;

    public McpAutoConnectService(McpServerCatalogService catalogService) {
        this(catalogService, loadKeywords());
    }

    // Package-private: permite injetar os gatilhos direto no teste, sem depender do recurso.
    McpAutoConnectService(McpServerCatalogService catalogService, Map<String, List<String>> keywords) {
        this.catalogService = catalogService;
        this.patternsByServer = compile(keywords);
    }

    /**
     * Conecta os servidores relevantes ao pedido que estão disponíveis e desconectados. Retorna os
     * ids efetivamente conectados. Best-effort: nunca lança.
     */
    public List<String> connectRelevant(String requestText, List<String> workspaceRoots) {
        if (requestText == null || requestText.isBlank()) {
            return List.of();
        }
        try {
            String normalized = normalize(requestText);
            List<String> candidates = catalogService.catalog(workspaceRoots).stream()
                    .filter(ServerDescriptor::available)
                    .filter(descriptor -> !descriptor.connected())
                    .filter(descriptor -> matchesIntent(normalized, descriptor.id()))
                    .map(ServerDescriptor::id)
                    .toList();
            if (candidates.isEmpty()) {
                return List.of();
            }
            List<String> connected = catalogService.connect(candidates, workspaceRoots).stream()
                    .filter(ConnectionResult::connected)
                    .map(ConnectionResult::serverName)
                    .toList();
            if (!connected.isEmpty()) {
                logger.info("Auto-connect: servidores MCP conectados sob demanda para o pedido: {}", connected);
            }
            return connected;
        } catch (RuntimeException exception) {
            logger.debug("Auto-connect de servidores MCP falhou; seguindo sem eles", exception);
            return List.of();
        }
    }

    private boolean matchesIntent(String normalizedRequest, String serverId) {
        List<Pattern> patterns = patternsByServer.get(serverId);
        if (patterns == null) {
            return false;
        }
        return patterns.stream()
                .anyMatch(pattern -> pattern.matcher(normalizedRequest).find());
    }

    private static Map<String, List<Pattern>> compile(Map<String, List<String>> keywords) {
        Map<String, List<Pattern>> compiled = new LinkedHashMap<>();
        keywords.forEach((serverId, words) -> compiled.put(
                serverId,
                words.stream()
                        .map(McpAutoConnectService::normalize)
                        .filter(word -> !word.isBlank())
                        .map(word -> Pattern.compile("\\b" + Pattern.quote(word) + "\\b"))
                        .toList()));
        return compiled;
    }

    private static Map<String, List<String>> loadKeywords() {
        try {
            ClassPathResource resource = new ClassPathResource(KEYWORDS_RESOURCE);
            try (var input = resource.getInputStream()) {
                return parseKeywords(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException exception) {
            logger.warn("Não foi possível ler {}; auto-connect por intenção desativado", KEYWORDS_RESOURCE, exception);
            return Map.of();
        }
    }

    // Package-private: faz o parsing do MD (id do servidor + linha "Gatilhos:"). Testável isolado.
    static Map<String, List<String>> parseKeywords(String markdown) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        String currentServerId = null;
        for (String rawLine : markdown.split("\\r?\\n")) {
            String line = rawLine.strip();
            if (line.startsWith("### ")) {
                currentServerId = line.substring(4).strip();
            } else if (currentServerId != null && line.toLowerCase(Locale.ROOT).startsWith("gatilhos:")) {
                List<String> keywords = Arrays.stream(
                                line.substring(line.indexOf(':') + 1).split(","))
                        .map(String::strip)
                        .filter(keyword -> !keyword.isBlank())
                        .toList();
                if (!keywords.isEmpty()) {
                    result.put(currentServerId, keywords);
                }
            }
        }
        return result;
    }

    private static String normalize(String value) {
        String lowered = value.toLowerCase(Locale.ROOT);
        return Normalizer.normalize(lowered, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
