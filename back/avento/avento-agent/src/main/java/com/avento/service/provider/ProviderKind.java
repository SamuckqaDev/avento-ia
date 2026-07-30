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
    OLLAMA("/api/tags", false),

    /** Qualquer servidor que fale o formato da OpenAI: vLLM, LM Studio, DGX, TGI. */
    OPENAI_COMPATIBLE("/v1/models", false),

    /** Google Gemini. */
    GEMINI("/v1beta/models", true),

    /** Anthropic Claude. */
    ANTHROPIC("/v1/models", true);

    private final String modelsPath;
    private final boolean requiresApiKey;

    ProviderKind(String modelsPath, boolean requiresApiKey) {
        this.modelsPath = modelsPath;
        this.requiresApiKey = requiresApiKey;
    }

    /** Caminho de listagem de modelos, relativo à base URL do provedor. */
    public String modelsPath() {
        return modelsPath;
    }

    /** Sem chave, o provedor não responde — a interface deve exigir antes de ativar. */
    public boolean requiresApiKey() {
        return requiresApiKey;
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
