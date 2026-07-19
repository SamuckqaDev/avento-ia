package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpeechTextNormalizerTest {

    private final SpeechTextNormalizer normalizer = new SpeechTextNormalizer();

    @Test
    void removesMarkdownEmojiAndMetricsWithoutLosingTheMessage() {
        String spoken = normalizer.normalize("""
                **Avento** abriu o `Terminal`. 😊

                - Processo concluído.
                ⏱️ 1.2s | 📦 20 tokens
                """);

        assertThat(spoken)
                .isEqualTo("Avento abriu o Terminal. Processo concluído.")
                .doesNotContain("asterisco", "emoji", "tokens");
    }

    @Test
    void speaksLinkLabelsAndSkipsCodeBlocks() {
        String spoken = normalizer.normalize("""
                Veja a [documentação](https://example.com/docs).
                ```java
                System.out.println("não leia");
                ```
                Tudo pronto.
                """);

        assertThat(spoken).isEqualTo("Veja a documentação. Tudo pronto.");
    }
}
