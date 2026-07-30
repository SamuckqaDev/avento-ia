package com.avento.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Recupera a chamada de ferramenta que o modelo escreveu como texto em vez de emitir no campo
 * nativo.
 *
 * <p>Modelos pequenos fazem isso o tempo todo. Sem esta recuperação o sintoma é o pior possível: o
 * modelo escreve o JSON certo na resposta, o usuário vê a intenção correta na tela, e nada acontece.
 */
public final class TextualToolCallParser {

    /**
     * Marca o INÍCIO de um objeto JSON que parece uma chamada de ferramenta.
     *
     * <p>Só o início: o objeto completo sai por contagem de chaves em {@link #extractBalancedJson},
     * porque regex não fecha aninhamento. O padrão antigo {@code ([^}]+\})} parava na PRIMEIRA
     * {@code '}'}, truncando {@code {"tool":"fetch","argument":{"url":...}}} e descartando em
     * silêncio exatamente as chamadas que o modelo pequeno mais produz.
     */
    public static final Pattern PSEUDO_JSON_TOOL_START =
            Pattern.compile("\\{\\s*\"(?:tool|name|action|function)\"\\s*:");

    // Modelos pequenos variam o envelope. Aceitar todos custa nada e evita descartar a intencao
    // correta por causa do nome do invólucro.
    private static final List<String> ARGUMENT_WRAPPERS =
            List.of("arguments", "parameters", "argument", "args", "input", "params");

    private static final List<String> NAME_FIELDS = List.of("tool", "name", "action", "function");

    private TextualToolCallParser() {}

    /**
     * Extrai o objeto JSON completo a partir de {@code openIndex} (que deve apontar para um
     * {@code '{'}), respeitando aninhamento e strings — chaves dentro de {@code "..."} não contam.
     *
     * @return o objeto, ou {@code null} se ele nunca fecha
     */
    public static String extractBalancedJson(String text, int openIndex) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = openIndex; index < text.length(); index++) {
            char current = text.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(openIndex, index + 1);
                }
            }
        }
        return null;
    }

    /** Os argumentos, venham eles num invólucro conhecido ou soltos no objeto raiz. */
    public static JsonNode extractArguments(JsonNode parsed) {
        for (String wrapper : ARGUMENT_WRAPPERS) {
            if (parsed.has(wrapper) && parsed.get(wrapper).isObject()) {
                return parsed.get(wrapper);
            }
        }
        ObjectNode args = parsed.deepCopy();
        NAME_FIELDS.forEach(args::remove);
        return args;
    }

    /** O nome da ferramenta, no primeiro dos campos aceitos que vier preenchido. */
    public static String toolName(JsonNode parsed) {
        for (String field : NAME_FIELDS) {
            String value = parsed.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
