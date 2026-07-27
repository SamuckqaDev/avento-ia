package com.avento.service.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ImagePromptTranslatorTest {

    private static final String OLLAMA = "http://localhost:11434";

    private static ImagePromptTranslator translator(boolean enabled, String response, boolean fail) {
        return new ImagePromptTranslator(new ObjectMapper(), OLLAMA, enabled, "qwen3:8b", 45, "30m") {
            @Override
            protected String requestTranslation(String prompt) throws Exception {
                if (fail) {
                    throw new IllegalStateException("Ollama returned HTTP 500");
                }
                return response;
            }
        };
    }

    @Test
    void translatesThePromptThroughTheModel() {
        ImagePromptTranslator translator =
                translator(true, "a parking lot with a single red car, photorealistic", false);

        assertThat(translator.toEnglish("Um estacionamento com um carro vermelho"))
                .isEqualTo("a parking lot with a single red car, photorealistic");
    }

    @Test
    void stripsQuotesAndCollapsesWhitespaceFromTheModelOutput() {
        ImagePromptTranslator translator = translator(true, "\"a red  car\n in a parking lot\"", false);

        assertThat(translator.toEnglish("Um carro vermelho")).isEqualTo("a red car in a parking lot");
    }

    @Test
    void keepsTheOriginalPromptWhenTheModelFails() {
        ImagePromptTranslator translator = translator(true, null, true);

        assertThat(translator.toEnglish("Um carro vermelho")).isEqualTo("Um carro vermelho");
    }

    @Test
    void keepsTheOriginalPromptWhenTheModelReturnsNothing() {
        ImagePromptTranslator translator = translator(true, "   ", false);

        assertThat(translator.toEnglish("Um carro vermelho")).isEqualTo("Um carro vermelho");
    }

    @Test
    void keepsTheOriginalPromptWhenTheModelRambles() {
        ImagePromptTranslator translator = translator(true, "sure! here is the translation ".repeat(40), false);

        assertThat(translator.toEnglish("Um carro vermelho")).isEqualTo("Um carro vermelho");
    }

    @Test
    void skipsTranslationWhenDisabled() {
        ImagePromptTranslator translator = translator(false, "a red car", false);

        assertThat(translator.toEnglish("Um carro vermelho")).isEqualTo("Um carro vermelho");
    }
}
