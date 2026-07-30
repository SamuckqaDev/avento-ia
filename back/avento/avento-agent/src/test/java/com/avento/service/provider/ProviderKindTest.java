package com.avento.service.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * O tipo do provedor é o que dirige o sistema: listagem de modelos, roteamento e capacidades saem
 * dele. Antes havia uma divisão binária (servidor "do sistema" contra "nuvem pessoal") com os nomes
 * dos modelos escritos no código — o que não descreve um Ollama na rede, um DGX compatível com
 * OpenAI e o Gemini como o mesmo conceito.
 */
class ProviderKindTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void fallsBackToLocalForUnknownOrMissingValues() {
        assertThat(ProviderKind.from(null)).isEqualTo(ProviderKind.OLLAMA);
        assertThat(ProviderKind.from("")).isEqualTo(ProviderKind.OLLAMA);
        assertThat(ProviderKind.from("coisa-que-nao-existe")).isEqualTo(ProviderKind.OLLAMA);
    }

    @Test
    void isCaseInsensitive() {
        assertThat(ProviderKind.from("gemini")).isEqualTo(ProviderKind.GEMINI);
        assertThat(ProviderKind.from("  GeMiNi  ")).isEqualTo(ProviderKind.GEMINI);
    }

    // Valores gravados antes deste enum existir nao podem virar OLLAMA por engano.
    @Test
    void understandsHistoricalNames() {
        assertThat(ProviderKind.from("GOOGLE")).isEqualTo(ProviderKind.GEMINI);
        assertThat(ProviderKind.from("OPENAI")).isEqualTo(ProviderKind.OPENAI_COMPATIBLE);
        assertThat(ProviderKind.from("vllm")).isEqualTo(ProviderKind.OPENAI_COMPATIBLE);
        assertThat(ProviderKind.from("DGX")).isEqualTo(ProviderKind.OPENAI_COMPATIBLE);
        assertThat(ProviderKind.from("CLAUDE")).isEqualTo(ProviderKind.ANTHROPIC);
    }

    @Test
    void declaresWhetherAKeyIsRequired() {
        assertThat(ProviderKind.OLLAMA.requiresApiKey()).isFalse();
        assertThat(ProviderKind.GEMINI.requiresApiKey()).isTrue();
        assertThat(ProviderKind.ANTHROPIC.requiresApiKey()).isTrue();
    }

    // Cada provedor devolve o nome do modelo num lugar diferente.
    @Test
    void parsesOllamaModelNames() throws Exception {
        var body = MAPPER.readTree("{\"models\":[{\"name\":\"qwen3.5:9b\"},{\"name\":\"granite4.1:8b\"}]}");

        assertThat(ProviderModelCatalog.parseModels(ProviderKind.OLLAMA, body))
                .containsExactly("qwen3.5:9b", "granite4.1:8b");
    }

    @Test
    void parsesOpenAiCompatibleModelNames() throws Exception {
        var body = MAPPER.readTree("{\"data\":[{\"id\":\"llama-3.3-70b\"},{\"id\":\"mixtral\"}]}");

        assertThat(ProviderModelCatalog.parseModels(ProviderKind.OPENAI_COMPATIBLE, body))
                .containsExactly("llama-3.3-70b", "mixtral");
    }

    // O Gemini prefixa com "models/", e o resto da API espera o nome puro.
    @Test
    void stripsTheModelsPrefixFromGeminiNames() throws Exception {
        var body = MAPPER.readTree(
                "{\"models\":[{\"name\":\"models/gemini-2.5-flash\",\"supportedGenerationMethods\":[\"generateContent\"]}]}");

        assertThat(ProviderModelCatalog.parseModels(ProviderKind.GEMINI, body)).containsExactly("gemini-2.5-flash");
    }

    // A mesma lista traz modelos de embedding, que nao servem para conversa e apareceriam como
    // opcao quebrada no seletor.
    @Test
    void dropsGeminiModelsThatCannotGenerateContent() throws Exception {
        var body = MAPPER.readTree("{\"models\":["
                + "{\"name\":\"models/gemini-2.5-flash\",\"supportedGenerationMethods\":[\"generateContent\"]},"
                + "{\"name\":\"models/text-embedding-004\",\"supportedGenerationMethods\":[\"embedContent\"]}]}");

        assertThat(ProviderModelCatalog.parseModels(ProviderKind.GEMINI, body)).containsExactly("gemini-2.5-flash");
    }

    @Test
    void survivesAnUnexpectedBody() throws Exception {
        var body = MAPPER.readTree("{\"erro\":\"chave invalida\"}");

        assertThat(ProviderModelCatalog.parseModels(ProviderKind.GEMINI, body)).isEmpty();
        assertThat(ProviderModelCatalog.parseModels(ProviderKind.OLLAMA, body)).isEmpty();
    }
}
