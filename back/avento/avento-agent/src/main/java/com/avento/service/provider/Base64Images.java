package com.avento.service.provider;

/**
 * Descobre o tipo da imagem anexada olhando o próprio conteúdo.
 *
 * <p>O formato interno herdou do Ollama a lista {@code images: ["<base64>"]} — sem nome de arquivo e
 * sem tipo declarado. Os provedores de nuvem exigem o tipo: o Gemini em {@code inlineData.mimeType},
 * o Anthropic em {@code source.media_type} e o formato da OpenAI dentro da própria data URI. Sem
 * ele a imagem é recusada, e o efeito para quem usa é o modelo dizer que não recebeu anexo nenhum.
 *
 * <p>Chutar {@code image/png} sempre resolveria o caso comum e falharia no anexo de câmera, que é
 * JPEG. Os primeiros bytes de cada formato têm assinatura estável, e em base64 ela vira um prefixo
 * de texto fixo — dá para reconhecer sem decodificar a imagem inteira.
 */
final class Base64Images {

    private static final String DATA_URI_PREFIX = "data:";

    private Base64Images() {}

    /** Tipo MIME da imagem. Cai em {@code image/png} quando a assinatura não é reconhecida. */
    static String mediaType(String image) {
        if (image == null || image.isBlank()) {
            return "image/png";
        }
        String trimmed = image.trim();
        // Já veio como data URI: o tipo está declarado, não há o que adivinhar.
        if (trimmed.startsWith(DATA_URI_PREFIX)) {
            int separator = trimmed.indexOf(';');
            if (separator > DATA_URI_PREFIX.length()) {
                return trimmed.substring(DATA_URI_PREFIX.length(), separator);
            }
        }
        String data = payload(trimmed);
        if (data.startsWith("/9j/")) {
            return "image/jpeg";
        }
        if (data.startsWith("R0lGOD")) {
            return "image/gif";
        }
        if (data.startsWith("UklGR")) {
            return "image/webp";
        }
        // "iVBORw0KGgo" é PNG, e PNG também é o palpite menos arriscado para o desconhecido.
        return "image/png";
    }

    /** Base64 puro, com a data URI removida quando houver uma. */
    static String payload(String image) {
        if (image == null) {
            return "";
        }
        String trimmed = image.trim();
        if (!trimmed.startsWith(DATA_URI_PREFIX)) {
            return trimmed;
        }
        int comma = trimmed.indexOf(',');
        return comma < 0 ? trimmed : trimmed.substring(comma + 1);
    }

    /** Data URI completa, formato que o dialeto da OpenAI espera em {@code image_url}. */
    static String dataUri(String image) {
        String trimmed = image == null ? "" : image.trim();
        if (trimmed.startsWith(DATA_URI_PREFIX)) {
            return trimmed;
        }
        return DATA_URI_PREFIX + mediaType(trimmed) + ";base64," + payload(trimmed);
    }
}
