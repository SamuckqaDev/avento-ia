package com.avento.service.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * A rede que torna a migração do schema artesanal para {@code @Tool} segura.
 *
 * <p>O payload de ferramentas entra no PREFIXO do prompt. Se o schema gerado diferir do artesanal,
 * converter 42 ferramentas muda o prefixo de toda conversa — e o custo de esquema por rodada é o que
 * já levou uma rodada a passar de seis minutos nesta máquina.
 */
class ToolSchemaNormalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolSchemaNormalizer normalizer = new ToolSchemaNormalizer(mapper);

    /** O equivalente anotado do {@code read_file}, com a mesma descrição palavra por palavra. */
    static class ReadFileTool {
        @Tool(name = "read_file", description = "Le o conteudo de um arquivo dentro de um workspace autorizado.")
        public String readFile(@ToolParam(description = "Caminho absoluto do arquivo autorizado.") String path) {
            return "";
        }
    }

    private String handBuiltSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties")
                .putObject("path")
                .put("type", "string")
                .put("description", "Caminho absoluto do arquivo autorizado.");
        schema.putArray("required").add("path");
        schema.put("additionalProperties", false);
        return schema.toString();
    }

    private String generatedSchema() {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(new ReadFileTool())
                .build()
                .getToolCallbacks();
        return callbacks[0].getToolDefinition().inputSchema();
    }

    /**
     * <b>O teste que autoriza a migração.</b> Normalizado, o schema gerado é byte a byte igual ao
     * artesanal — então trocar as 42 declarações à mão por anotações não muda o prefixo do prompt e
     * não invalida o cache do llama.cpp.
     */
    @Test
    void theGeneratedSchemaMatchesTheHandBuiltOneOnceNormalised() {
        assertThat(normalizer.normalise(generatedSchema())).isEqualTo(handBuiltSchema());
    }

    /**
     * Sem normalizar, o custo é real e recorrente.
     *
     * <p>Medido: 162 para 280 caracteres neste schema, +72%. Com o teto de 18 ferramentas seriam
     * cerca de 2.100 caracteres a mais <b>em toda rodada</b>. Este teste existe para que ninguém
     * "simplifique" o normalizador achando que ele é cosmético.
     */
    @Test
    void showsWhyNormalisingIsNotCosmetic() {
        String raw = generatedSchema();

        assertThat(raw.length())
                .as("o schema gerado vem formatado e com $schema; sem normalizar isso vai no prompt")
                .isGreaterThan(handBuiltSchema().length());
        assertThat(raw).contains("$schema");
        assertThat(normalizer.normalise(raw)).doesNotContain("$schema");
    }

    /**
     * O dialeto sai porque declara o formato para um VALIDADOR, e não há validador do outro lado: quem
     * lê é o modelo. É texto que o usuário paga em toda mensagem sem nada em troca.
     */
    @Test
    void dropsTheDialectKeyThatNoReaderOnTheOtherSideUses() {
        String schema = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\"}";

        assertThat(normalizer.normalise(schema)).isEqualTo("{\"type\":\"object\"}");
    }

    /**
     * Entrada ilegível volta inalterada.
     *
     * <p>Normalizar é otimização de tamanho; trocar um schema grande por uma exceção seria pior,
     * porque o modelo perderia a ferramenta inteira em vez de recebê-la um pouco maior.
     */
    @Test
    void returnsUnreadableInputUntouchedInsteadOfLosingTheTool() {
        assertThat(normalizer.normalise("{ isto nao e json ")).isEqualTo("{ isto nao e json ");
        assertThat(normalizer.normalise(null)).isNull();
        assertThat(normalizer.normalise("")).isEmpty();
    }

    /** Schema que já está compacto e sem dialeto passa igual — normalizar duas vezes não muda nada. */
    @Test
    void isIdempotent() {
        String once = normalizer.normalise(generatedSchema());

        assertThat(normalizer.normalise(once)).isEqualTo(once);
    }
}
