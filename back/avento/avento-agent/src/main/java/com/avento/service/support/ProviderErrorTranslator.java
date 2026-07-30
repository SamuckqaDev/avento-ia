package com.avento.service.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforma a resposta de erro de um provedor de nuvem em uma linha que o usuário consegue agir.
 *
 * <p>O corpo chega como um muro de JSON aninhado — a mesma violação repetida, links de documentação,
 * metadados. Despejar isso na conversa esconde a única frase que importa dentro de um parágrafo de
 * chaves, e o usuário precisa garimpar para descobrir o que fazer.
 */
public final class ProviderErrorTranslator {

    private static final Pattern MESSAGE_FIELD = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern RETRY_DELAY = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)s\"");

    private static final int MAX_RAW_BODY_CHARS = 300;
    private static final int MAX_DETAIL_CHARS = 400;

    private ProviderErrorTranslator() {}

    /** Descreve um erro HTTP do provedor em uma linha. */
    public static String describeProviderError(int status, String body) {
        String resumo = "O provedor retornou HTTP " + status;
        if (body == null || body.isBlank()) {
            return resumo + ".";
        }
        Matcher matcher = MESSAGE_FIELD.matcher(body);
        if (!matcher.find()) {
            return resumo + ": " + truncate(body, MAX_RAW_BODY_CHARS);
        }
        String detalhe =
                matcher.group(1).replace("\\n", " ").replaceAll("\\s+", " ").trim();
        // A mesma violacao costuma vir repetida; corta antes da repeticao.
        int repeticao = detalhe.indexOf("Invalid JSON payload received.", 1);
        if (repeticao > 0) {
            detalhe = detalhe.substring(0, repeticao).trim();
        }
        return resumo + ": " + truncate(detalhe, MAX_DETAIL_CHARS);
    }

    /**
     * Resume um 429 do provedor.
     *
     * <p>O que importa é se a cota é ZERO — modelo fora do plano, e esperar não resolve — ou se é
     * limite temporário, e em quanto tempo tentar de novo. As duas situações vêm com o mesmo código
     * HTTP e pedem reações opostas.
     */
    public static String quotaHint(String message, String model) {
        boolean semCota = message.contains("limit: 0");
        Matcher matcher = RETRY_DELAY.matcher(message);
        String espera = matcher.find() ? matcher.group(1) : "";

        if (semCota) {
            return "\n> ⚠️ O modelo `" + model + "` tem cota **zero** no seu plano — nao e limite"
                    + " atingido, e modelo indisponivel na conta. No Gemini, os modelos **pro**"
                    + " costumam exigir faturamento habilitado; os **flash** sao os do plano"
                    + " gratuito. Troque em Configuracoes > Modelos & Provedores, ou habilite"
                    + " faturamento no Google.\n";
        }
        return "\n> ⚠️ Limite de uso do provedor atingido"
                + (espera.isBlank() ? "" : "; tente de novo em " + espera + "s")
                + ".\n";
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }
}
