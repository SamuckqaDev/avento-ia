package com.avento.service.intent;

import com.avento.service.support.HeuristicWordLists;
import com.avento.service.tools.ToolCapability;
import com.avento.service.tools.ToolCapabilityRegistry;
import java.text.Normalizer;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class IntentRouter {

    // Palavras-chave por intent editaveis sem recompilar: ver
    // src/main/resources/agent/heuristics/intent-keywords.txt
    private static final Map<AgentIntent, List<String>> INTENT_KEYWORDS = loadIntentKeywords();

    private final ToolCapabilityRegistry toolRegistry;
    private final IntentEmbeddingClassifier embeddingClassifier;

    public IntentRouter(ToolCapabilityRegistry toolRegistry, IntentEmbeddingClassifier embeddingClassifier) {
        this.toolRegistry = toolRegistry;
        this.embeddingClassifier = embeddingClassifier;
    }

    private static Map<AgentIntent, List<String>> loadIntentKeywords() {
        Map<String, List<String>> sections = HeuristicWordLists.loadSections("agent/heuristics/intent-keywords.txt");
        Map<AgentIntent, List<String>> keywords = new EnumMap<>(AgentIntent.class);
        sections.forEach((section, words) -> keywords.put(AgentIntent.valueOf(section), words));
        return Map.copyOf(keywords);
    }

    // Uniao de dois sinais: palavra-chave exata (rapido, sempre disponivel) e
    // similaridade de significado por embedding (mais robusto a frase nova,
    // cai de volta sozinho se o Ollama estiver indisponivel). Qualquer um dos
    // dois marcando uma intent e suficiente — o objetivo e reduzir falso
    // negativo, nao exigir que os dois concordem.
    public IntentProfile classify(String message) {
        String normalizedMessage = normalize(message);
        EnumSet<AgentIntent> intents = EnumSet.noneOf(AgentIntent.class);

        INTENT_KEYWORDS.forEach((intent, keywords) -> {
            if (containsAny(normalizedMessage, keywords)) {
                intents.add(intent);
            }
        });

        embeddingClassifier.classify(message).ifPresent(intents::addAll);

        return IntentProfile.of(intents);
    }

    // Reclassificar a mensagem a cada ferramenta testada custaria uma chamada
    // de embedding por ferramenta (dezenas por request com MCP externo
    // conectado). Quem itera varias ferramentas para a mesma mensagem deve
    // chamar classify() uma vez e reusar o profile neste overload.
    public boolean shouldExposeTool(String toolName, IntentProfile profile) {
        boolean isBrowserAutomationTool = toolName.startsWith("browser_") || toolName.startsWith("puppeteer_");
        boolean isWebReaderTool =
                toolName.equals("fetch") || toolName.startsWith("searxng_") || toolName.equals("web_url_read");
        boolean isDeveloperTool = toolName.startsWith("git_")
                || toolName.startsWith("docker_")
                || toolName.equals("execute_sql")
                || toolName.equals("search_objects");

        return toolRegistry
                .find(toolName)
                .map(tool -> shouldExposeRegisteredTool(tool, profile))
                .orElseGet(() -> {
                    if (isBrowserAutomationTool || isWebReaderTool) {
                        return profile.has(AgentIntent.WEB) || profile.has(AgentIntent.EXTERNAL_MCP);
                    }
                    if (isDeveloperTool) {
                        return profile.has(AgentIntent.TERMINAL) || profile.has(AgentIntent.EXTERNAL_MCP);
                    }
                    return profile.has(AgentIntent.EXTERNAL_MCP);
                });
    }

    public boolean shouldExposeTool(String toolName, String message) {
        return shouldExposeTool(toolName, classify(message));
    }

    private boolean shouldExposeRegisteredTool(ToolCapability tool, IntentProfile profile) {
        return switch (tool.category()) {
            case FILESYSTEM ->
                profile.has(AgentIntent.FILE_READ)
                        || profile.has(AgentIntent.FILE_WRITE)
                        || profile.has(AgentIntent.TERMINAL);
            case PROJECT_SCAFFOLD -> profile.has(AgentIntent.FILE_WRITE);
            case TERMINAL -> profile.has(AgentIntent.TERMINAL);
            case SCREEN -> profile.has(AgentIntent.AUTOMATION);
            case IMAGE -> profile.has(AgentIntent.IMAGE);
            case DOCUMENT -> profile.has(AgentIntent.IMAGE);
            case MACOS_APP, BROWSER, URL, FINDER, SHORTCUT -> profile.has(AgentIntent.AUTOMATION);
            case MCP_EXTERNAL -> profile.has(AgentIntent.EXTERNAL_MCP);
        };
    }

    private String normalize(String message) {
        if (message == null) {
            return "";
        }
        return Normalizer.normalize(message.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean containsAny(String text, List<String> needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
