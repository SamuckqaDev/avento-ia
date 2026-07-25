package com.avento.service.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Serviço de Descoberta Progressiva de Ferramentas (Progressive Tool Disclosure).
 * Em vez de expor 40+ schemas pesados de ferramentas no contexto fixo (num_ctx),
 * o Avento disponibiliza um catálogo de metadados leve. O modelo pesquisa capacidades
 * via 'search_capabilities' e ativa apenas as ferramentas necessárias via 'activate_tools',
 * com o conjunto ativo mantido em cache Redis por execução (TTL 15 min).
 */
@Service
public class ToolCatalogService {

    private static final Logger logger = LoggerFactory.getLogger(ToolCatalogService.class);
    private static final String REDIS_RUN_TOOLS_PREFIX = "avento:run:";
    private static final Duration ACTIVE_TOOLS_TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;
    private final ToolCapabilityRegistry capabilityRegistry;
    private final ObjectMapper mapper;

    public ToolCatalogService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ToolCapabilityRegistry capabilityRegistry,
            ObjectMapper mapper) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.capabilityRegistry = capabilityRegistry;
        this.mapper = mapper;
    }

    public record CapabilitySummary(String toolId, String name, String category, String shortDescription) {}

    /**
     * Retorna o catálogo leve de metadados de todas as capacidades disponíveis.
     */
    public List<CapabilitySummary> searchCapabilities(String query) {
        return searchCapabilities(query, List.of());
    }

    /**
     * Busca no catálogo leve: registro local + capacidades extras informadas pelo chamador
     * (ferramentas de servidores MCP conectados, servidores disponíveis para conectar etc.).
     * A pesquisa casa por token: qualquer termo da consulta presente em nome/categoria/descrição
     * marca a capacidade — uma consulta como "gerar pdf relatorio" não pode exigir a frase inteira.
     */
    public List<CapabilitySummary> searchCapabilities(String query, List<CapabilitySummary> extras) {
        List<CapabilitySummary> catalog = new ArrayList<>();
        capabilityRegistry
                .all()
                .forEach(def -> catalog.add(new CapabilitySummary(
                        def.name(),
                        def.name(),
                        def.category() == null ? "" : def.category().name(),
                        def.summary() == null ? "" : def.summary())));
        Set<String> known = new HashSet<>();
        catalog.forEach(summary -> known.add(summary.toolId()));
        for (CapabilitySummary extra : extras == null ? List.<CapabilitySummary>of() : extras) {
            if (extra != null && known.add(extra.toolId())) {
                catalog.add(extra);
            }
        }

        String normQuery = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        String[] queryTokens = normQuery.split("[^\\p{L}\\p{N}_]+");
        List<CapabilitySummary> results = new ArrayList<>();
        for (CapabilitySummary summary : catalog) {
            String haystack = (summary.name() + " " + summary.category() + " " + summary.shortDescription())
                    .toLowerCase(Locale.ROOT);
            if (normQuery.isBlank() || matchesAnyToken(haystack, queryTokens)) {
                results.add(summary);
            }
        }
        return results;
    }

    private boolean matchesAnyToken(String haystack, String[] tokens) {
        for (String token : tokens) {
            if (token.length() > 2 && haystack.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ativa um conjunto de ferramentas no Redis para a execução (runId) atual. Somente nomes do
     * registro local são aceitos — para catálogos maiores use o overload com {@code validNames}.
     */
    public Set<String> activateTools(String runId, List<String> toolNames) {
        return activateTools(runId, toolNames, Set.of());
    }

    /**
     * Ativa ferramentas validando contra {@code validNames} (o catálogo da rodada: locais +
     * MCP externas conectadas) além do registro local. O registro sozinho rejeitava toda
     * ferramenta externa (fetch, git_*, ...), tornando a ativação inútil justamente para as
     * ferramentas que mais precisam dela.
     */
    public Set<String> activateTools(String runId, List<String> toolNames, Set<String> validNames) {
        Set<String> activeSet = getActiveTools(runId);
        if (toolNames != null) {
            for (String tool : toolNames) {
                if (tool == null || tool.isBlank()) {
                    continue;
                }
                String trimmed = tool.trim();
                if (capabilityRegistry.find(trimmed).isPresent()
                        || (validNames != null && validNames.contains(trimmed))) {
                    activeSet.add(trimmed);
                }
            }
        }

        saveActiveTools(runId, activeSet);
        logger.info("Ferramentas ativas para a execução {}: {}", runId, activeSet);
        return activeSet;
    }

    /**
     * Recupera o conjunto de ferramentas ativas da execução atual no Redis.
     */
    public Set<String> getActiveTools(String runId) {
        Set<String> active = new HashSet<>();
        // Sempre adiciona as ferramentas de infraestrutura básica
        active.add("search_capabilities");
        active.add("activate_tools");

        if (runId == null || runId.isBlank() || redisTemplate == null) {
            return active;
        }

        try {
            String key = REDIS_RUN_TOOLS_PREFIX + runId + ":tools";
            Set<String> cached = redisTemplate.opsForSet().members(key);
            if (cached != null && !cached.isEmpty()) {
                active.addAll(cached);
            }
        } catch (Exception exception) {
            logger.debug("Could not read active tools from Redis for run {}", runId, exception);
        }

        return active;
    }

    private void saveActiveTools(String runId, Set<String> tools) {
        if (runId == null || runId.isBlank() || redisTemplate == null) {
            return;
        }

        try {
            String key = REDIS_RUN_TOOLS_PREFIX + runId + ":tools";
            redisTemplate.delete(key);
            if (!tools.isEmpty()) {
                redisTemplate.opsForSet().add(key, tools.toArray(new String[0]));
                redisTemplate.expire(key, ACTIVE_TOOLS_TTL);
            }
        } catch (Exception exception) {
            logger.debug("Could not save active tools to Redis for run {}", runId, exception);
        }
    }
}
