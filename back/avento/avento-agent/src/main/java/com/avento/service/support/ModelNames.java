package com.avento.service.support;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O que dá para saber de um modelo pelo nome dele.
 *
 * <p>Ollama não expõe "isto é um modelo de visão" nem "isto aguenta ferramentas" — o que chega é
 * um nome e, às vezes, uma família e um tamanho. Todo o resto é inferido daí, e essas inferências
 * estavam espalhadas pelo meio do laço do agente, onde ninguém conseguia testá-las sem levantar o
 * serviço inteiro.
 *
 * <p>Funções puras de propósito: o padrão de chat e o de visão entram por parâmetro em vez de vir
 * de campo, para que a classe não precise saber que existe configuração.
 */
public final class ModelNames {

    // A partir daqui o modelo já não cabe folgado na memória de uma máquina de 16 GB junto do
    // resto da pilha (Postgres, Redis, ComfyUI) — ver o aviso de modelo pesado na interface.
    private static final long HEAVY_MODEL_BYTES = 4_000_000_000L;

    private static final Pattern PARAMETER_SIZE = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?b)");

    private ModelNames() {}

    /**
     * Nome com cara de modelo local do Ollama ({@code familia:tag}), que um provedor de nuvem não
     * conhece. Mandar {@code qwen3.5:9b} para o Gemini foi o que gerou o primeiro 404.
     */
    public static boolean isLocalModelName(String model) {
        return model != null && model.contains(":") && !model.startsWith("http");
    }

    public static boolean isVisionModel(String modelName, String family) {
        String normalizedName = lower(modelName);
        String normalizedFamily = lower(family);
        return normalizedName.contains("vision")
                || normalizedName.contains("llava")
                || normalizedName.contains("bakllava")
                || normalizedName.contains("moondream")
                || normalizedName.contains("minicpm-v")
                || normalizedName.matches(".*qwen[^:]*vl.*")
                || normalizedFamily.contains("mllama")
                || normalizedFamily.contains("qwen25vl")
                || normalizedFamily.contains("qwen2vl")
                || normalizedFamily.contains("llava")
                || normalizedFamily.contains("gemma3");
    }

    /** Serve para conversar: qualquer coisa que não seja embedding nem geração de imagem. */
    public static boolean isChatModel(String modelName) {
        if (isBlank(modelName)) {
            return false;
        }
        String normalized = lower(modelName);
        return !normalized.contains("embed")
                && !normalized.contains("flux")
                && !normalized.contains("stable-diffusion")
                && !normalized.contains("sdxl")
                && !normalized.contains("image-turbo")
                && !normalized.contains("z-image")
                && !normalized.contains("text-to-image");
    }

    public static boolean isImageModel(String modelName) {
        if (isBlank(modelName)) {
            return false;
        }
        String normalized = lower(modelName);
        return normalized.contains("flux")
                || normalized.contains("stable-diffusion")
                || normalized.contains("sdxl")
                || normalized.contains("image-turbo")
                || normalized.contains("z-image")
                || normalized.contains("text-to-image")
                || normalized.contains("diffusion");
    }

    public static boolean isHeavyModel(String modelName, long sizeBytes, String parameterSize) {
        if (sizeBytes >= HEAVY_MODEL_BYTES) {
            return true;
        }

        String normalizedName = lower(modelName);
        if (normalizedName.contains("70b")
                || normalizedName.contains("32b")
                || normalizedName.contains("14b")
                || normalizedName.contains("13b")
                || normalizedName.contains("8b")
                || normalizedName.contains("7b")) {
            return true;
        }

        return lower(parameterSize).matches(".*\\b([7-9]|[1-9][0-9]+)b\\b.*");
    }

    public static String inferFamily(String modelName) {
        String normalized = lower(modelName);
        if (normalized.contains("llama")) return "llama";
        if (normalized.contains("qwen")) return "qwen";
        if (normalized.contains("mistral")) return "mistral";
        if (normalized.contains("gemma")) return "gemma";
        if (normalized.contains("deepseek")) return "deepseek";
        if (normalized.contains("glm") || normalized.contains("chatglm")) return "glm";
        return "local";
    }

    public static String inferParameterSize(String modelName) {
        Matcher matcher = PARAMETER_SIZE.matcher(modelName == null ? "" : modelName);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : "";
    }

    /** Cai no padrão quando o nome não serve para conversar (vazio, embedding, modelo de imagem). */
    public static String normalizeChatModel(String modelName, String defaultChatModel) {
        if (isBlank(modelName) || !isChatModel(modelName)) {
            return defaultChatModel;
        }
        return modelName;
    }

    /** O padrão configurado, ou outra tag da mesma família — {@code qwen3.5} casa {@code qwen3.5:9b}. */
    public static boolean isRecommendedModel(String modelName, String defaultChatModel) {
        String normalized = lower(modelName);
        String defaultNormalized = lower(defaultChatModel);
        String defaultFamily = defaultNormalized.contains(":")
                ? defaultNormalized.substring(0, defaultNormalized.indexOf(':'))
                : defaultNormalized;
        return normalized.equals(defaultNormalized)
                || normalized.equals(defaultFamily)
                || normalized.startsWith(defaultFamily + ":");
    }

    public static boolean isPreferredVisionModel(String modelName, String defaultVisionModel) {
        return modelName != null && modelName.equalsIgnoreCase(defaultVisionModel);
    }

    public static String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first : second == null ? "" : second;
    }

    /**
     * Qual modelo atende o pedido: o escolhido na tela, o gravado em Provedores, ou o padrão.
     *
     * <p>A regra existe porque as duas escolhas são de lugares diferentes. O seletor do cabeçalho é
     * por mensagem; a tela de Provedores grava um modelo ativo. Quando o pedido traz um nome, ele
     * ganha — trocar no seletor tem de ter efeito imediato, senão a interface mente.
     *
     * <p>Só um pedido EM BRANCO conta como "não escolhi" — é o que o front manda enquanto o seletor
     * nunca foi tocado. A versão anterior também tratava "pediu exatamente o default" como ausência
     * de escolha, e o efeito era escolher granite no seletor e continuar rodando outro modelo, sem
     * aviso nenhum.
     *
     * @param remoteTransport um provedor de nuvem está atendendo; nomes locais não servem para ele
     */
    public static String chooseChatModel(
            String requestedModel, String configuredModel, String defaultChatModel, boolean remoteTransport) {
        String requested = requestedModel == null ? "" : requestedModel.trim();
        String configured = configuredModel == null ? "" : configuredModel.trim();

        if (remoteTransport) {
            if (!requested.isEmpty() && !isLocalModelName(requested)) {
                return requested;
            }
            return configured.isEmpty() ? normalizeChatModel(requested, defaultChatModel) : configured;
        }

        // Pedido em branco e a UNICA forma de "nao escolhi": o front manda string vazia enquanto o
        // seletor nunca foi tocado, e o nome escolhido em qualquer outro caso. Tratar "pediu o
        // default" como "nao pediu nada" fazia escolher o granite no seletor nao ter efeito algum.
        if (requested.isEmpty()) {
            return configured.isEmpty() ? defaultChatModel : configured;
        }
        return normalizeChatModel(requested, defaultChatModel);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
