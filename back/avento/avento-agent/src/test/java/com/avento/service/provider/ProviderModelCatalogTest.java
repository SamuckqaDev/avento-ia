package com.avento.service.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A lista de modelos tem de vir do provedor, não de nomes escritos no código: lista chumbada
 * envelhece e oferece modelo que talvez não exista na conta de quem usa — a API recusa e a pessoa
 * não entende por quê.
 *
 * <p>Cada provedor devolve o nome num lugar diferente, e é isso que estes testes prendem.
 */
class ProviderModelCatalogTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    @Test
    void readsOllamaTags() throws Exception {
        JsonNode body = json("{\"models\":[{\"name\":\"qwen3.5:9b\"},{\"name\":\"granite4.1:8b\"}]}");

        assertThat(ProviderModelCatalog.parseModels(ProviderKind.OLLAMA, body))
                .containsExactly("qwen3.5:9b", "granite4.1:8b");
    }

    @Test
    void readsOpenAiCompatibleList() throws Exception {
        JsonNode body = json("{\"data\":[{\"id\":\"llama-3.1-70b\"},{\"id\":\"mixtral\"}]}");

        assertThat(ProviderModelCatalog.parseModels(ProviderKind.OPENAI_COMPATIBLE, body))
                .containsExactly("llama-3.1-70b", "mixtral");
    }

    // O Gemini devolve "models/gemini-2.5-flash"; o resto da API espera o nome puro.
    @Test
    void stripsTheModelsPrefixFromGeminiNames() throws Exception {
        JsonNode body = json("{\"models\":[{\"name\":\"models/gemini-2.5-flash\","
                + "\"supportedGenerationMethods\":[\"generateContent\"]}]}");

        assertThat(ProviderModelCatalog.parseModels(ProviderKind.GEMINI, body)).containsExactly("gemini-2.5-flash");
    }

    // A mesma lista traz modelos de embedding, que apareceriam como opcao quebrada no seletor.
    @Test
    void keepsOnlyGeminiModelsThatGenerateContent() throws Exception {
        JsonNode body = json("{\"models\":["
                + "{\"name\":\"models/gemini-2.5-flash\",\"supportedGenerationMethods\":[\"generateContent\"]},"
                + "{\"name\":\"models/text-embedding-004\",\"supportedGenerationMethods\":[\"embedContent\"]}]}");

        assertThat(ProviderModelCatalog.parseModels(ProviderKind.GEMINI, body)).containsExactly("gemini-2.5-flash");
    }

    // A listagem inclui modelo que a API recusa ao usar — o gemini-2.5-flash aparece e devolve 404
    // dizendo "no longer available to new users". Oferecer no seletor um nome que so falha depois e
    // pior que nao oferecer.
    @Test
    void hidesModelsTheProviderMarksAsRetired() throws Exception {
        JsonNode body = json("{\"models\":["
                + "{\"name\":\"models/gemini-2.5-flash\",\"description\":\"This model is no longer available"
                + " to new users.\",\"supportedGenerationMethods\":[\"generateContent\"]},"
                + "{\"name\":\"models/gemini-2.0-flash\",\"supportedGenerationMethods\":[\"generateContent\"]}]}");

        assertThat(ProviderModelCatalog.parseModels(ProviderKind.GEMINI, body)).containsExactly("gemini-2.0-flash");
    }

    @Test
    void doesNotFilterWhenGeminiOmitsTheMethods() throws Exception {
        JsonNode body = json("{\"models\":[{\"name\":\"models/gemini-novo\"}]}");

        assertThat(ProviderModelCatalog.parseModels(ProviderKind.GEMINI, body)).containsExactly("gemini-novo");
    }

    // O seletor de imagem e outro: com o Gemini ativo, listar checkpoint do ComfyUI ofereceria um
    // nome que o provedor ativo nao conhece.
    @Test
    void separatesGeminiImageModels() throws Exception {
        JsonNode body = json("{\"models\":["
                + "{\"name\":\"models/gemini-2.5-flash\",\"supportedGenerationMethods\":[\"generateContent\"]},"
                + "{\"name\":\"models/imagen-3.0-generate-002\",\"supportedGenerationMethods\":[\"predict\"]}]}");

        assertThat(ProviderModelCatalog.parseImageModels(ProviderKind.GEMINI, body))
                .containsExactly("imagen-3.0-generate-002");
    }

    @Test
    void hasNoImageModelsForOllama() throws Exception {
        JsonNode body = json("{\"models\":[{\"name\":\"qwen3.5:9b\"}]}");

        assertThat(ProviderModelCatalog.parseImageModels(ProviderKind.OLLAMA, body))
                .isEmpty();
    }

    @Test
    void survivesAnUnexpectedBody() throws Exception {
        assertThat(ProviderModelCatalog.parseModels(ProviderKind.OLLAMA, json("{}")))
                .isEmpty();
        assertThat(ProviderModelCatalog.parseModels(ProviderKind.GEMINI, json("{\"erro\":\"chave invalida\"}")))
                .isEmpty();
    }

    @Test
    void mapsHistoricProviderNamesToKinds() {
        assertThat(ProviderKind.from("GOOGLE")).isEqualTo(ProviderKind.GEMINI);
        assertThat(ProviderKind.from("DGX")).isEqualTo(ProviderKind.OPENAI_COMPATIBLE);
        assertThat(ProviderKind.from("openai")).isEqualTo(ProviderKind.OPENAI_COMPATIBLE);
        assertThat(ProviderKind.from(null)).isEqualTo(ProviderKind.OLLAMA);
        assertThat(ProviderKind.from("coisa-desconhecida")).isEqualTo(ProviderKind.OLLAMA);
    }

    // ---- Ollama escondido atras de um endereco "compativel com OpenAI" -----------------------

    /**
     * Apontar "compativel com OpenAI" para um Ollama e valido e comum, e era silenciosamente pior:
     * o formato da OpenAI nao tem num_ctx, entao o pedido de janela do Avento era descartado e o
     * servidor subia com os 4096 padrao dele, truncando 62% do prompt sem avisar.
     */
    @Test
    void recognizesAnOllamaByItsTagsBody() throws Exception {
        JsonNode body = json("{\"models\":[{\"name\":\"qwen3.5:35b\"},{\"name\":\"bge-m3:latest\"}]}");

        assertThat(ProviderModelCatalog.isOllamaTagsBody(body)).isTrue();
    }

    /** Ollama recem-instalado, sem modelo nenhum, continua sendo um Ollama. */
    @Test
    void recognizesAnOllamaWithNoModelsPulledYet() throws Exception {
        assertThat(ProviderModelCatalog.isOllamaTagsBody(json("{\"models\":[]}")))
                .isTrue();
    }

    /**
     * Um 200 nao basta: proxy e pagina de erro tambem respondem 200, e promover o provedor a Ollama
     * por causa disso mandaria a conversa para um endpoint que nao existe.
     */
    @Test
    void doesNotMistakeAnyTwoHundredForAnOllama() throws Exception {
        assertThat(ProviderModelCatalog.isOllamaTagsBody(json("{\"data\":[{\"id\":\"gpt-4\"}]}")))
                .isFalse();
        assertThat(ProviderModelCatalog.isOllamaTagsBody(json("{\"models\":\"nenhum\"}")))
                .isFalse();
        assertThat(ProviderModelCatalog.isOllamaTagsBody(json("{}"))).isFalse();
    }

    /** Lista de modelos sem nome nao e o formato do /api/tags. */
    @Test
    void doesNotAcceptAModelsArrayWithoutNames() throws Exception {
        assertThat(ProviderModelCatalog.isOllamaTagsBody(json("{\"models\":[{\"id\":\"algo\"}]}")))
                .isFalse();
    }
}
