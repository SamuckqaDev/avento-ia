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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IntentRouter {

    // Palavras-chave por intent editaveis sem recompilar: ver
    // src/main/resources/agent/heuristics/intent-keywords.txt
    private static final Map<AgentIntent, List<String>> INTENT_KEYWORDS = loadIntentKeywords();

    private final ToolCapabilityRegistry toolRegistry;
    private final IntentEmbeddingClassifier embeddingClassifier;

    // Quando as palavras-chave ja acharam alguma intent, pular o embedding: em maquina de 16GB, a
    // chamada ao nomic por mensagem pode despejar o modelo de chat da memoria e forcar um reload caro.
    // O embedding vira rede de seguranca — so roda quando a palavra-chave nao achou nada (frase nova).
    // Para voltar a somar sempre os dois sinais: AVENTO_AGENT_INTENT_EMBEDDING_FALLBACK_ONLY=false.
    private final boolean embeddingFallbackOnly;

    /** Abaixo disto, uma edicao muda o sentido da palavra em vez de corrigir digitacao. */
    private static final int MIN_FUZZY_LENGTH = 6;

    public IntentRouter(
            ToolCapabilityRegistry toolRegistry,
            IntentEmbeddingClassifier embeddingClassifier,
            @Value("${avento.agent.intent-embedding-fallback-only:true}") boolean embeddingFallbackOnly) {
        this.toolRegistry = toolRegistry;
        this.embeddingClassifier = embeddingClassifier;
        this.embeddingFallbackOnly = embeddingFallbackOnly;
    }

    private static Map<AgentIntent, List<String>> loadIntentKeywords() {
        Map<String, List<String>> sections = HeuristicWordLists.loadSections("agent/heuristics/intent-keywords.txt");
        Map<AgentIntent, List<String>> keywords = new EnumMap<>(AgentIntent.class);
        sections.forEach((section, words) -> keywords.put(AgentIntent.valueOf(section), words));
        return Map.copyOf(keywords);
    }

    // Dois sinais para a intencao: palavra-chave exata (rapido, sem custo de modelo) e
    // similaridade de significado por embedding (robusto a frase nova, cai de volta sozinho se o
    // Ollama estiver indisponivel). Qualquer um marcando uma intent basta — o objetivo e reduzir
    // falso negativo. Em modo fallback-only, o embedding so roda quando a palavra-chave nao achou
    // nada, para nao pagar uma chamada de modelo por mensagem quando o rapido ja resolveu.
    public IntentProfile classify(String message) {
        String normalizedMessage = normalize(message);
        EnumSet<AgentIntent> intents = EnumSet.noneOf(AgentIntent.class);

        INTENT_KEYWORDS.forEach((intent, keywords) -> {
            if (containsAny(normalizedMessage, keywords)) {
                intents.add(intent);
            }
        });

        if (!embeddingFallbackOnly || intents.isEmpty()) {
            embeddingClassifier.classify(message).ifPresent(intents::addAll);
        }

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
            // DOCUMENT tools (generate_pdf) answer to their own intent. IMAGE stays as a
            // fallback so "gera uma imagem e faz um pdf dela" keeps both tools on the table.
            case DOCUMENT -> profile.has(AgentIntent.DOCUMENT) || profile.has(AgentIntent.IMAGE);
            case MACOS_APP, BROWSER, URL, FINDER, SHORTCUT -> profile.has(AgentIntent.AUTOMATION);
            case MCP_EXTERNAL -> profile.has(AgentIntent.EXTERNAL_MCP);
            // remember é barato e sem efeito colateral (só cria sugestão pendente); deixamos sempre
            // disponível para o modelo poder propor um fato durável em qualquer contexto de trabalho.
            case MEMORY -> true;
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
        return containsAnyApproximately(text, needles);
    }

    /**
     * Segunda passada tolerante a UMA letra errada, trocada, faltando ou sobrando.
     *
     * <p>Existe para o matcher parar de depender de digitação perfeita. Caso real: "Pode pesquisr
     * sobre" — uma letra a menos — deixou a rodada sem a ferramenta de web, e o modelo respondeu
     * prometendo uma busca que nunca aconteceu. A alternativa seria catalogar cada variação de
     * pergunta, o que cresce sem fim.
     *
     * <p>Corrigir o texto do usuário seria pior: numa conversa de desenvolvimento ele contém caminho
     * de arquivo, nome de modelo e comando, e reescrever isso em silêncio quebra o pedido. Aqui o
     * texto original segue intacto para o modelo; a tolerância vive só na decisão de qual ferramenta
     * expor.
     */
    private boolean containsAnyApproximately(String text, List<String> needles) {
        String[] words = text.split(" ");
        for (String needle : needles) {
            // Palavra curta com 1 erro vira outra palavra ("cor" -> "cot"): so vale a partir de 6.
            if (needle.length() < MIN_FUZZY_LENGTH || needle.indexOf(' ') >= 0) {
                continue;
            }
            for (String word : words) {
                if (Math.abs(word.length() - needle.length()) <= 1 && withinOneEdit(word, needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Levenshtein com corte em 1: para assim que passa de uma edição, sem montar a matriz toda. */
    static boolean withinOneEdit(String left, String right) {
        if (left.equals(right)) {
            return true;
        }
        int lengthDifference = left.length() - right.length();
        if (Math.abs(lengthDifference) > 1) {
            return false;
        }

        String longer = lengthDifference >= 0 ? left : right;
        String shorter = lengthDifference >= 0 ? right : left;

        int i = 0;
        while (i < shorter.length() && longer.charAt(i) == shorter.charAt(i)) {
            i++;
        }
        if (i == shorter.length()) {
            // Sobrou no maximo um caractere no fim.
            return longer.length() - shorter.length() <= 1;
        }

        int end = 0;
        while (end < shorter.length() - i
                && longer.charAt(longer.length() - 1 - end) == shorter.charAt(shorter.length() - 1 - end)) {
            end++;
        }
        return i + end >= shorter.length() - (longer.length() == shorter.length() ? 1 : 0)
                && i + end >= longer.length() - 1;
    }
}
