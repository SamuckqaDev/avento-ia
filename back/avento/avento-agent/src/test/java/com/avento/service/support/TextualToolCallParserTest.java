package com.avento.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Modelos locais pequenos frequentemente "chamam" uma ferramenta escrevendo JSON no texto da
 * resposta em vez de usar o canal nativo de tool call. Dois defeitos faziam toda chamada dessas
 * sumir em silêncio: a regex de extração parava na PRIMEIRA chave de fechamento (truncando
 * qualquer payload com argumento aninhado), e o nome era validado contra o registro local, que não
 * conhece ferramentas MCP externas como {@code fetch} — justamente as que o modelo mais textualiza.
 */
class TextualToolCallParserTest {

    @Test
    void extractsNestedJsonObjectCompletely() {
        String text = "antes { \"tool\": \"fetch\", \"argument\": { \"url\": \"https://x\" } } depois";

        String extracted = TextualToolCallParser.extractBalancedJson(text, text.indexOf('{'));

        assertThat(extracted).isEqualTo("{ \"tool\": \"fetch\", \"argument\": { \"url\": \"https://x\" } }");
    }

    @Test
    void ignoresBracesInsideStrings() {
        String text = "{ \"tool\": \"terminal_run\", \"command\": \"echo '}'\" }";

        assertThat(TextualToolCallParser.extractBalancedJson(text, 0)).isEqualTo(text);
    }

    @Test
    void returnsNullWhenObjectNeverCloses() {
        assertThat(TextualToolCallParser.extractBalancedJson("{ \"tool\": \"fetch\", ", 0))
                .isNull();
    }

    @Test
    void extractsOnlyTheFirstObjectWhenSeveralAreConcatenated() {
        String text = "{\"tool\":\"a\"}{\"tool\":\"b\"}";

        assertThat(TextualToolCallParser.extractBalancedJson(text, 0)).isEqualTo("{\"tool\":\"a\"}");
    }

    @Test
    void handlesEscapedQuotesInsideStrings() {
        String text = "{ \"tool\": \"write_file\", \"content\": \"diz \\\"oi\\\" }\" }";

        assertThat(TextualToolCallParser.extractBalancedJson(text, 0)).isEqualTo(text);
    }
}
