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

    /**
     * Se a janela de contexto que o provedor DECLARA é a que ele realmente carrega.
     *
     * <p>Num serviço gerenciado é: a Google e a Anthropic alocam a janela do modelo e cobram por
     * ela, então o número anunciado é utilizável e o Avento pode gastar até ali.
     *
     * <p>Num Ollama — local ou noutra máquina da rede — não é. O {@code /api/show} devolve o TETO do
     * modelo ({@code qwen3.5:35b} declara 262144), enquanto a instância sobe com o padrão do
     * servidor, que é 4096. Confiar no declarado aqui produz os dois desastres de uma vez: no
     * caminho nativo o Avento pede um {@code num_ctx} cujo KV cache passa de 40 GB, e no caminho
     * compatível com OpenAI o pedido é descartado e o Ollama corta o prompt em silêncio.
     *
     * <p>A pergunta certa nunca foi "é local ou remoto" — é "quem decide o tamanho da janela".
     */
    /**
     * Se o provedor nomeia os modelos do jeito dele, em vez de aceitar {@code familia:tag}.
     *
     * <p>O Gemini quer {@code gemini-2.0-flash} e a Anthropic quer {@code claude-sonnet-4}; mandar
     * {@code qwen3.5:9b} para eles rende 404 sem explicacao. Ja o Ollama e os servidores compativeis
     * com OpenAI usam exatamente {@code familia:tag} — ali recusar esse formato e recusar o unico
     * nome valido, que era o que matava o seletor de modelos com um Ollama na rede.
     */
    public boolean hasOwnModelNamespace() {
        return switch (this) {
            case GEMINI, ANTHROPIC -> true;
            case OLLAMA, OPENAI_COMPATIBLE -> false;
        };
    }

    /**
     * Se da para PEDIR o tamanho da janela na propria requisicao.
     *
     * <p>O {@code /api/chat} do Ollama aceita {@code options.num_ctx} e recarrega o modelo com o que
     * for pedido — verificado: pedindo 4096 ele sobe com 4096, pedindo 32768 sobe com 32768. Ja o
     * protocolo da OpenAI nao tem esse campo, entao num servidor compativel a janela e decidida por
     * quem sobe o servidor e o Avento so pode se adaptar a ela.
     *
     * <p>A distincao importa para nao criar uma armadilha: onde da para pedir, limitar o pedido pela
     * janela JA carregada prenderia o Avento nela para sempre — leria 4096, pediria 4096, e nunca
     * cresceria.
     */
    public boolean canRequestContextWindow() {
        return this == OLLAMA;
    }

    public boolean managesItsOwnContext() {
        return switch (this) {
            case GEMINI, ANTHROPIC -> true;
            case OLLAMA, OPENAI_COMPATIBLE -> false;
        };
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
