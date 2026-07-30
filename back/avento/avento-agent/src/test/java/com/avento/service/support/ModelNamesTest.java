package com.avento.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Com provedor remoto ativo, o modelo escolhido no chat era descartado em favor do gravado. O log
 * mostrava o problema em duas linhas seguidas:
 *
 * <pre>
 * Agent run starting for chat 34 with model gemini-3.1-pro-preview
 * Agent round 1 starting build for model gemini-2.5-flash
 * </pre>
 *
 * <p>Trocar de modelo no chat não tinha efeito nenhum. O gravado só deve entrar quando o pedido vem
 * sem modelo, ou traz um nome local que o provedor remoto não conhece — mandar {@code qwen3.5:9b}
 * para o Gemini foi o que gerou o primeiro 404.
 */
class ModelNamesTest {

    // Modelo do Ollama usa familia:tag; o de nuvem, nao.
    @Test
    void recognisesOllamaStyleNames() {
        assertThat(ModelNames.isLocalModelName("qwen3.5:9b")).isTrue();
        assertThat(ModelNames.isLocalModelName("granite4.1:8b")).isTrue();
        assertThat(ModelNames.isLocalModelName("qwen2.5vl:7b")).isTrue();
    }

    @Test
    void treatsCloudNamesAsRemote() {
        assertThat(ModelNames.isLocalModelName("gemini-3.1-pro-preview")).isFalse();
        assertThat(ModelNames.isLocalModelName("gemini-2.0-flash")).isFalse();
        assertThat(ModelNames.isLocalModelName("gpt-4o-mini")).isFalse();
        assertThat(ModelNames.isLocalModelName("claude-sonnet-4")).isFalse();
    }

    @Test
    void handlesEmptyAndNull() {
        assertThat(ModelNames.isLocalModelName(null)).isFalse();
        assertThat(ModelNames.isLocalModelName("")).isFalse();
    }

    // As inferencias abaixo eram privadas dentro do AgentService e nao tinham teste nenhum: so
    // davam sinal quando a lista de modelos aparecia errada na tela.

    @Test
    void separatesChatModelsFromEmbeddingAndImageOnes() {
        assertThat(ModelNames.isChatModel("qwen3.5:9b")).isTrue();
        assertThat(ModelNames.isChatModel("granite4.1:8b")).isTrue();
        assertThat(ModelNames.isChatModel("nomic-embed-text")).isFalse();
        assertThat(ModelNames.isChatModel("flux-schnell")).isFalse();
        assertThat(ModelNames.isChatModel("sdxl-turbo")).isFalse();
        assertThat(ModelNames.isChatModel(null)).isFalse();
        assertThat(ModelNames.isChatModel("  ")).isFalse();
    }

    @Test
    void recognisesImageModels() {
        assertThat(ModelNames.isImageModel("flux-schnell")).isTrue();
        assertThat(ModelNames.isImageModel("stable-diffusion-xl")).isTrue();
        assertThat(ModelNames.isImageModel("RealVisXL_V5.0_fp16.safetensors")).isFalse();
        assertThat(ModelNames.isImageModel("qwen3.5:9b")).isFalse();
    }

    @Test
    void recognisesVisionModelsByNameOrFamily() {
        assertThat(ModelNames.isVisionModel("qwen2.5vl:7b", "")).isTrue();
        assertThat(ModelNames.isVisionModel("llava:13b", "")).isTrue();
        assertThat(ModelNames.isVisionModel("moondream", "")).isTrue();
        // O nome nao diz nada, a familia diz.
        assertThat(ModelNames.isVisionModel("meu-modelo", "mllama")).isTrue();
        assertThat(ModelNames.isVisionModel("granite4.1:8b", "granite")).isFalse();
    }

    @Test
    void callsAModelHeavyBySizeNameOrParameterCount() {
        assertThat(ModelNames.isHeavyModel("qualquer", 5_000_000_000L, "")).isTrue();
        assertThat(ModelNames.isHeavyModel("llama3:70b", 0L, "")).isTrue();
        assertThat(ModelNames.isHeavyModel("modelo", 0L, "13B")).isTrue();
        assertThat(ModelNames.isHeavyModel("gemma:2b", 0L, "2B")).isFalse();
    }

    @Test
    void infersFamilyAndParameterSizeFromTheName() {
        assertThat(ModelNames.inferFamily("qwen3.5:9b")).isEqualTo("qwen");
        assertThat(ModelNames.inferFamily("chatglm:6b")).isEqualTo("glm");
        assertThat(ModelNames.inferFamily("granite4.1:8b")).isEqualTo("local");

        assertThat(ModelNames.inferParameterSize("qwen3.5:9b")).isEqualTo("9B");
        assertThat(ModelNames.inferParameterSize("llama3.2:3.2b")).isEqualTo("3.2B");
        assertThat(ModelNames.inferParameterSize("modelo-sem-numero")).isEmpty();
    }

    @Test
    void fallsBackToTheDefaultWhenTheNameCannotChat() {
        assertThat(ModelNames.normalizeChatModel("qwen3.5:9b", "granite4.1:8b")).isEqualTo("qwen3.5:9b");
        assertThat(ModelNames.normalizeChatModel("nomic-embed-text", "granite4.1:8b"))
                .isEqualTo("granite4.1:8b");
        assertThat(ModelNames.normalizeChatModel(null, "granite4.1:8b")).isEqualTo("granite4.1:8b");
    }

    @Test
    void marksAnyTagOfTheDefaultFamilyAsRecommended() {
        assertThat(ModelNames.isRecommendedModel("granite4.1:8b", "granite4.1:8b"))
                .isTrue();
        // Outra tag da mesma familia continua sendo "o modelo padrao" para quem le a lista.
        assertThat(ModelNames.isRecommendedModel("granite4.1:2b", "granite4.1:8b"))
                .isTrue();
        assertThat(ModelNames.isRecommendedModel("granite4.1", "granite4.1:8b")).isTrue();
        assertThat(ModelNames.isRecommendedModel("qwen3.5:9b", "granite4.1:8b")).isFalse();
    }

    @Test
    void picksTheFirstNonBlank() {
        assertThat(ModelNames.firstNonBlank("qwen", "local")).isEqualTo("qwen");
        assertThat(ModelNames.firstNonBlank("  ", "local")).isEqualTo("local");
        assertThat(ModelNames.firstNonBlank(null, null)).isEmpty();
    }
}
