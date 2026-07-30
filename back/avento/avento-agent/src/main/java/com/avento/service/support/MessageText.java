package com.avento.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * Leitura do que o usuário realmente escreveu, separado do que o Avento injetou em volta.
 *
 * <p>A mensagem que chega ao modelo raramente é só o pedido: vem com blocos de contexto colados
 * antes — raízes do workspace, ambiente local, análise do projeto. Toda heurística que olha "o que
 * o usuário quer" precisa ver o pedido, não o envelope, e é por isso que essas funções aparecem
 * dezenas de vezes no laço do agente.
 */
public final class MessageText {

    // Palavras-chave editaveis sem recompilar: ver agent/heuristics/*.txt
    private static final Set<String> CASUAL_PHRASES =
            Set.copyOf(HeuristicWordLists.loadLines("agent/heuristics/casual-phrases.txt"));
    private static final Set<String> PROJECT_ACTION_WORDS =
            Set.copyOf(HeuristicWordLists.loadLines("agent/heuristics/project-action-words.txt"));

    /** Acima disto já não é "oi, tudo bem" — é pedido, mesmo que soe informal. */
    private static final int CASUAL_MAX_CHARS = 80;

    private MessageText() {}

    /** O conteúdo da última mensagem do usuário, ou {@code null} se não houver nenhuma. */
    public static String lastUserMessage(ArrayNode messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            JsonNode message = messages.get(index);
            if ("user".equals(message.path("role").asText())) {
                return message.path("content").asText("");
            }
        }
        return null;
    }

    /**
     * O pedido, sem os blocos de contexto que o backend injetou antes dele.
     *
     * <p>Sem isto, uma heurística que procura nome de aplicativo casa com o "Apps detectados:
     * Finder, Terminal, ..." do próprio bloco de ambiente — que sempre lista o Finder primeiro — em
     * vez do app que o usuário pediu.
     */
    public static String extractDirectUserRequest(String message) {
        int requestStart = directUserRequestStart(message);
        if (requestStart < 0) {
            return message;
        }

        String extracted = message.substring(requestStart).trim();
        return extracted.isBlank() ? message : extracted;
    }

    /** Minúsculas, sem acento e sem pontuação: a forma em que as heurísticas comparam. */
    public static String normalizeIntentText(String message) {
        if (message == null) {
            return "";
        }
        String withoutAccents = Normalizer.normalize(message.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return withoutAccents
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Conversa fiada — "oi", "valeu", "tudo bem". Uma palavra de ação sobre projeto desqualifica na
     * hora: "oi, roda os testes" é pedido, não cumprimento.
     */
    public static boolean isCasualUserMessage(String message) {
        String normalized = normalizeIntentText(message);
        if (normalized.isBlank() || normalized.length() > CASUAL_MAX_CHARS) {
            return false;
        }

        for (String actionWord : PROJECT_ACTION_WORDS) {
            if (normalized.contains(actionWord)) {
                return false;
            }
        }

        for (String casualPhrase : CASUAL_PHRASES) {
            if (normalized.equals(casualPhrase) || normalized.startsWith(casualPhrase + " ")) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsAny(String text, Iterable<String> values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Onde o pedido começa dentro da mensagem, ou {@code -1} se não há envelope.
     *
     * <p>Público porque a compactação de contexto precisa do índice, não do texto: ela corta o
     * contexto antes do pedido e preserva o pedido inteiro.
     */
    public static int directUserRequestStart(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        int requestStart = -1;
        int marker = lower.lastIndexOf("responda ao seguinte pedido");
        if (marker >= 0) {
            int separator = message.indexOf(":\n\n", marker);
            if (separator >= 0) {
                requestStart = separator + 3;
            }
        }

        String explicitMarker = "[pedido do usuário]";
        int explicitRequest = lower.lastIndexOf(explicitMarker);
        if (explicitRequest >= 0) {
            int explicitStart = explicitRequest + explicitMarker.length();
            while (explicitStart < message.length() && Character.isWhitespace(message.charAt(explicitStart))) {
                explicitStart++;
            }
            requestStart = Math.max(requestStart, explicitStart);
        }
        return requestStart;
    }
}
