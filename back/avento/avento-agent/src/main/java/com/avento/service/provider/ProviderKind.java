package com.avento.service.provider;

import java.util.Locale;

/**
 * Tipo do provedor — é ele que dirige o comportamento do sistema, não um par de flags.
 *
 * <p>Antes havia uma divisão binária (servidor "do sistema" contra "nuvem pessoal") com os nomes dos
 * modelos escritos no código. Isso não descreve a realidade: um Ollama na máquina da rede, um DGX
 * com endpoint compatível com OpenAI e o Gemini são três casos do mesmo conceito — um endereço, um
 * formato de API e, às vezes, uma chave.
 *
 * <p>Cada tipo declara aqui como listar modelos e do que precisa. Quem adiciona um provedor novo
 * mexe neste enum e na implementação de chat correspondente, não em condicionais espalhadas.
 */
public enum ProviderKind {

    /** Ollama nativo — local ou em outra máquina da rede. */
    OLLAMA("/api/tags", false, true),

    /** Qualquer servidor que fale o formato da OpenAI: vLLM, LM Studio, DGX, TGI. */
    OPENAI_COMPATIBLE("/v1/models", false, true),

    /** Google Gemini. */
    GEMINI("/v1beta/models", true, false),

    /** Anthropic Claude. */
    ANTHROPIC("/v1/models", true, false);

    private final String modelsPath;
    private final boolean requiresApiKey;
    private final boolean supportsLocalTools;

    ProviderKind(String modelsPath, boolean requiresApiKey, boolean supportsLocalTools) {
        this.modelsPath = modelsPath;
        this.requiresApiKey = requiresApiKey;
        this.supportsLocalTools = supportsLocalTools;
    }

    /** Caminho de listagem de modelos, relativo à base URL do provedor. */
    public String modelsPath() {
        return modelsPath;
    }

    /** Sem chave, o provedor não responde — a interface deve exigir antes de ativar. */
    public boolean requiresApiKey() {
        return requiresApiKey;
    }

    /**
     * Se o laço de rodadas com ferramentas locais funciona neste provedor.
     *
     * <p>Falso para os de nuvem enquanto o tool calling deles não estiver traduzido: o laço monta
     * toolset e interpreta {@code tool_calls} no formato do Ollama, e o Gemini devolve
     * {@code functionCall} dentro de {@code parts}. Reaproveitar daria erro silencioso.
     */
    public boolean supportsLocalTools() {
        return supportsLocalTools;
    }

    /** URL padrão do provedor, usada quando o usuário não informa uma. */
    public String defaultBaseUrl() {
        return switch (this) {
            case OLLAMA -> "http://127.0.0.1:11434";
            case OPENAI_COMPATIBLE -> "https://api.openai.com";
            case GEMINI -> "https://generativelanguage.googleapis.com";
            case ANTHROPIC -> "https://api.anthropic.com";
        };
    }

    /** Tolerante a valor ausente ou desconhecido: cai em OLLAMA, que é o modo local. */
    public static ProviderKind from(String raw) {
        if (raw == null || raw.isBlank()) {
            return OLLAMA;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (ProviderKind kind : values()) {
            if (kind.name().equals(normalized)) {
                return kind;
            }
        }
        // Nomes historicos gravados antes deste enum existir.
        return switch (normalized) {
            case "GOOGLE", "GEMINI_API" -> GEMINI;
            case "OPENAI", "VLLM", "LMSTUDIO", "LM_STUDIO", "DGX" -> OPENAI_COMPATIBLE;
            case "CLAUDE" -> ANTHROPIC;
            default -> OLLAMA;
        };
    }
}
