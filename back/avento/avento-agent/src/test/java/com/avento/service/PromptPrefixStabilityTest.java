package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Trava a causa-raiz da lentidão de julho de 2026: relógio de alta precisão dentro do system prompt.
 *
 * <p>O bloco de ambiente usava {@code LocalDateTime.now()}, com precisão de nanossegundo. Isso muda
 * a CADA requisição e fica no PREFIXO do prompt. O cache do Ollama casa por prefixo de tokens, então
 * cada mensagem invalidava tudo e reavaliava os ~8192 tokens inteiros — cerca de 50 segundos por
 * resposta, com rodadas de 70 a 87 segundos para gerar dois ou três mil caracteres.
 *
 * <p>Medido depois do conserto: avaliação de contexto de 4,0s para 0,1s a partir da segunda mensagem.
 *
 * <p><b>Por que este teste tem de existir:</b> um benchmark isolado não pega o defeito. Ele manda o
 * mesmo prompt duas vezes e portanto sempre acerta o cache. Só o uso real, que move o relógio entre
 * as chamadas, expõe o problema — e ninguém percebe até a máquina ficar lenta sem explicação.
 */
class PromptPrefixStabilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** hh:mm ou hh:mm:ss em qualquer lugar do prompt. */
    private static final Pattern CLOCK_TIME = Pattern.compile("\\b\\d{1,2}:\\d{2}(:\\d{2})?\\b");

    private PromptAssemblyService service() {
        PromptAssemblyService service = new PromptAssemblyService();
        service.setPolicyMode("maximum");
        service.setPolicyOverrideDirectory("");
        return service;
    }

    private ArrayNode userMessages(String... contents) {
        ArrayNode messages = MAPPER.createArrayNode();
        for (String content : contents) {
            messages.addObject().put("role", "user").put("content", content);
        }
        return messages;
    }

    @Test
    void buildsTheSamePromptForTheSameInputsAcrossCalls() throws Exception {
        PromptAssemblyService service = service();
        ArrayNode messages = userMessages("analisa esse projeto pra mim");

        String first = service.systemPrompt(messages, List.of("/tmp/projeto"), null);
        // Tempo suficiente para um relogio de segundos mudar entre as duas montagens. Com
        // LocalDateTime.now() no prompt, este assert falha; com LocalDate.now(), passa.
        Thread.sleep(1_100);
        String second = service.systemPrompt(messages, List.of("/tmp/projeto"), null);

        assertThat(second)
                .as("o system prompt tem de ser identico entre chamadas, senao o cache de prompt do "
                        + "Ollama e invalidado e cada mensagem reavalia o contexto inteiro")
                .isEqualTo(first);
    }

    @Test
    void carriesTheDateButNeverTheClock() {
        String prompt = service().systemPrompt(userMessages("oi"), List.of(), null);

        assertThat(prompt).contains("[Local Environment]");
        assertThat(prompt).contains(java.time.LocalDate.now().toString());
        assertThat(CLOCK_TIME.matcher(prompt).find())
                .as("nada de hora no prompt: precisao de dia basta ao agente e mantem o prefixo estavel")
                .isFalse();
    }

    /**
     * O prefixo é o que precisa ser estável, não o prompt inteiro. Este teste fixa a ORDEM: o que
     * varia por conversa fica depois do que é fixo, para que a parte cacheável seja a maior possível.
     */
    @Test
    void keepsTheVaryingBlocksAfterTheFixedOnes() {
        PromptAssemblyService service = service();

        String withoutRoots = service.systemPrompt(userMessages("oi"), List.of(), null);
        String withRoots = service.systemPrompt(userMessages("oi"), List.of("/tmp/projeto"), null);

        int divergence = 0;
        while (divergence < Math.min(withoutRoots.length(), withRoots.length())
                && withoutRoots.charAt(divergence) == withRoots.charAt(divergence)) {
            divergence++;
        }

        assertThat(withRoots).contains("[Workspace Roots]");
        assertThat(divergence)
                .as("as instrucoes fixas e a politica tem de vir antes do primeiro bloco variavel")
                .isGreaterThan(withRoots.indexOf("[Local Environment]"));
    }

    /**
     * A politica experimental do usuario vive fora do repositorio, em ~/.avento/policies. O prompt
     * tem de preferi-la a versao publica empacotada — sem isso, testar uma politica nova exigiria
     * editar (e arriscar commitar) o arquivo do repo.
     *
     * <p>Era um teste por reflexao em dois campos privados do AgentService.
     */
    @Test
    void prefersAnUntrackedLocalPolicyOverTheBundledOne(@TempDir java.nio.file.Path tempDir) throws Exception {
        String localPolicy = "POLÍTICA LOCAL PRIVADA PARA TESTE";
        java.nio.file.Files.writeString(tempDir.resolve("maximum.md"), localPolicy);

        PromptAssemblyService service = service();
        service.setPolicyOverrideDirectory(tempDir.toString());

        String prompt = service.systemPrompt(userMessages("Teste a política local."), List.of(), null);

        assertThat(prompt).contains(localPolicy).doesNotContain("LIMITES PARA CONTEÚDO NOVO");
    }

    @Test
    void fallsBackToTheBundledPolicyWhenThereIsNoOverride() {
        String prompt = service().systemPrompt(userMessages("oi"), List.of(), null);

        assertThat(prompt).contains("CONTENT POLICY — MAXIMUM MODE");
    }
}
