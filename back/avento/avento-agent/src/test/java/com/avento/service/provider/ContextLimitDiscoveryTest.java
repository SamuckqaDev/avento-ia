package com.avento.service.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * O tamanho de contexto era chute: um número fixo na configuração, igual para qualquer modelo. Num
 * modelo de janela grande isso truncava demais; num de janela pequena, estourava.
 *
 * <p>Os dois lados informam o valor real — o Ollama em {@code /api/show} e o Gemini em
 * {@code inputTokenLimit} da própria listagem. Perguntar é melhor que arbitrar.
 */
class ContextLimitDiscoveryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // O Ollama prefixa a chave com a familia do modelo, que muda de modelo para modelo.
    @Test
    void readsOllamaContextLengthWhateverThePrefix() throws Exception {
        String body = "{\"model_info\":{\"qwen35.context_length\":262144,\"qwen35.block_count\":48}}";

        assertThat(ProviderModelCatalog.ollamaContextLength(MAPPER.readTree(body)))
                .isEqualTo(262144);
    }

    @Test
    void readsOllamaContextLengthWithoutPrefix() throws Exception {
        String body = "{\"model_info\":{\"context_length\":8192}}";

        assertThat(ProviderModelCatalog.ollamaContextLength(MAPPER.readTree(body)))
                .isEqualTo(8192);
    }

    @Test
    void returnsZeroWhenOllamaOmitsTheInfo() throws Exception {
        assertThat(ProviderModelCatalog.ollamaContextLength(MAPPER.readTree("{\"model_info\":{}}")))
                .isZero();
    }

    @Test
    void readsGeminiInputTokenLimitForTheChosenModel() throws Exception {
        String body = "{\"models\":["
                + "{\"name\":\"models/gemini-2.0-flash\",\"inputTokenLimit\":1048576},"
                + "{\"name\":\"models/gemini-1.5-pro\",\"inputTokenLimit\":2097152}]}";

        assertThat(ProviderModelCatalog.declaredInputLimit(MAPPER.readTree(body), "gemini-1.5-pro"))
                .isEqualTo(2097152);
    }

    @Test
    void readsOpenAiCompatibleContextLength() throws Exception {
        String body = "{\"data\":[{\"id\":\"llama-3.1-70b\",\"context_length\":131072}]}";

        assertThat(ProviderModelCatalog.declaredInputLimit(MAPPER.readTree(body), "llama-3.1-70b"))
                .isEqualTo(131072);
    }

    // O vLLM (o caso do DGX) nomeia o campo de outro jeito; sem isto a janela voltaria ao chute.
    @Test
    void readsVllmMaxModelLen() throws Exception {
        String body = "{\"data\":[{\"id\":\"qwen2.5-72b\",\"max_model_len\":32768}]}";

        assertThat(ProviderModelCatalog.declaredInputLimit(MAPPER.readTree(body), "qwen2.5-72b"))
                .isEqualTo(32768);
    }

    // Modelo que nao esta na lista nao pode devolver o limite de outro.
    @Test
    void returnsZeroForAModelThatIsNotListed() throws Exception {
        String body = "{\"models\":[{\"name\":\"models/gemini-2.0-flash\",\"inputTokenLimit\":1048576}]}";

        assertThat(ProviderModelCatalog.declaredInputLimit(MAPPER.readTree(body), "gemini-9.9-inexistente"))
                .isZero();
    }
}
