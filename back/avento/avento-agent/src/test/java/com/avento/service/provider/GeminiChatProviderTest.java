package com.avento.service.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * A tradução é a parte que erra em silêncio: um papel com nome errado ou o prompt de sistema no
 * lugar errado não quebra o build nem lança exceção — só produz uma resposta pior, e ninguém
 * descobre. Por isso ela é estática e testada sem chave e sem chamada externa.
 */
class GeminiChatProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ArrayNode messages(String... roleAndContent) {
        ArrayNode messages = MAPPER.createArrayNode();
        for (int i = 0; i < roleAndContent.length; i += 2) {
            ObjectNode message = messages.addObject();
            message.put("role", roleAndContent[i]);
            message.put("content", roleAndContent[i + 1]);
        }
        return messages;
    }

    // O papel do assistente chama-se "model" no Gemini. Mandar "assistant" faz a API rejeitar.
    @Test
    void renamesTheAssistantRoleToModel() {
        ObjectNode request = GeminiChatProvider.toGeminiRequest(
                messages("user", "oi", "assistant", "olá", "user", "tudo bem?"), MAPPER);

        ArrayNode contents = (ArrayNode) request.get("contents");
        assertThat(contents).hasSize(3);
        assertThat(contents.get(0).get("role").asText()).isEqualTo("user");
        assertThat(contents.get(1).get("role").asText()).isEqualTo("model");
        assertThat(contents.get(2).get("role").asText()).isEqualTo("user");
    }

    // O prompt de sistema nao e uma mensagem no Gemini: vai em systemInstruction.
    @Test
    void movesSystemPromptsIntoSystemInstruction() {
        ObjectNode request =
                GeminiChatProvider.toGeminiRequest(messages("system", "Você é o Avento.", "user", "oi"), MAPPER);

        assertThat(request.path("systemInstruction")
                        .path("parts")
                        .get(0)
                        .path("text")
                        .asText())
                .isEqualTo("Você é o Avento.");
        assertThat((ArrayNode) request.get("contents")).hasSize(1);
    }

    @Test
    void joinsMultipleSystemPrompts() {
        ObjectNode request = GeminiChatProvider.toGeminiRequest(
                messages("system", "Regra A.", "system", "Regra B.", "user", "oi"), MAPPER);

        assertThat(request.path("systemInstruction")
                        .path("parts")
                        .get(0)
                        .path("text")
                        .asText())
                .isEqualTo("Regra A.\n\nRegra B.");
    }

    // Sem functionDeclarations, um resultado de ferramenta solto confunde o modelo.
    @Test
    void dropsToolMessagesInChatOnlyMode() {
        ObjectNode request = GeminiChatProvider.toGeminiRequest(
                messages("user", "que horas são?", "tool", "{\"result\":\"14:32\"}"), MAPPER);

        assertThat((ArrayNode) request.get("contents")).hasSize(1);
    }

    @Test
    void skipsEmptyMessages() {
        ObjectNode request = GeminiChatProvider.toGeminiRequest(messages("user", "", "user", "oi"), MAPPER);

        assertThat((ArrayNode) request.get("contents")).hasSize(1);
    }

    @Test
    void putsTextInsideParts() {
        ObjectNode request = GeminiChatProvider.toGeminiRequest(messages("user", "olá"), MAPPER);

        assertThat(request.path("contents")
                        .get(0)
                        .path("parts")
                        .get(0)
                        .path("text")
                        .asText())
                .isEqualTo("olá");
    }

    @Test
    void extractsTextFromAStreamEvent() {
        String event = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Olá\"}],\"role\":\"model\"}}]}";

        assertThat(GeminiChatProvider.textFromStreamEvent(event, MAPPER)).isEqualTo("Olá");
    }

    @Test
    void joinsSeveralPartsInOneEvent() {
        String event = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Olá \"},{\"text\":\"mundo\"}]}}]}";

        assertThat(GeminiChatProvider.textFromStreamEvent(event, MAPPER)).isEqualTo("Olá mundo");
    }

    // Eventos sem texto (metadados de seguranca, contagem de tokens) nao podem virar chunk vazio.
    @Test
    void returnsNullForEventsWithoutText() {
        assertThat(GeminiChatProvider.textFromStreamEvent("{\"usageMetadata\":{\"totalTokenCount\":7}}", MAPPER))
                .isNull();
        assertThat(GeminiChatProvider.textFromStreamEvent("{\"candidates\":[{\"finishReason\":\"STOP\"}]}", MAPPER))
                .isNull();
        assertThat(GeminiChatProvider.textFromStreamEvent("", MAPPER)).isNull();
        assertThat(GeminiChatProvider.textFromStreamEvent("[DONE]", MAPPER)).isNull();
        assertThat(GeminiChatProvider.textFromStreamEvent(null, MAPPER)).isNull();
    }

    // Fragmento cortado no meio do stream nao pode derrubar a resposta inteira.
    @Test
    void survivesMalformedJson() {
        assertThat(GeminiChatProvider.textFromStreamEvent("{\"candidates\":[{\"cont", MAPPER))
                .isNull();
    }
}
