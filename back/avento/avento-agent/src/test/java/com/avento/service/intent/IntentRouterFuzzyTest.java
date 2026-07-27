package com.avento.service.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.service.tools.ToolCapabilityRegistry;
import org.junit.jupiter.api.Test;

/**
 * O matcher dependia de digitação perfeita: "Pode pesquisr sobre" — uma letra a menos — deixou a
 * rodada sem a ferramenta de web, e o modelo respondeu prometendo uma busca que nunca aconteceu.
 *
 * <p>A alternativa seria catalogar cada variação de pergunta, o que cresce sem fim, ou corrigir o
 * texto do usuário — pior ainda, porque numa conversa de desenvolvimento ele carrega caminho de
 * arquivo, nome de modelo e comando. Aqui o texto original segue intacto: a tolerância vive só na
 * decisão de qual ferramenta expor.
 */
class IntentRouterFuzzyTest {

    private final IntentRouter router =
            new IntentRouter(new ToolCapabilityRegistry(), new IntentEmbeddingClassifier(null, 0.55, 2000), true);

    @Test
    void toleratesAMissingLetter() {
        assertTrue(router.shouldExposeTool("fetch", "Pode pesquisr sobre isso"));
    }

    @Test
    void toleratesASwappedLetter() {
        assertTrue(router.shouldExposeTool("fetch", "quero a cotacap do dolar"));
    }

    @Test
    void toleratesAnExtraLetter() {
        assertTrue(router.shouldExposeTool("fetch", "me da as noticiass de hoje"));
    }

    // Duas letras erradas ja e outra palavra: melhor nao expor do que expor errado.
    @Test
    void stopsAtOneEdit() {
        assertFalse(router.shouldExposeTool("fetch", "corrige o mtdo desse arquivo"));
    }

    @Test
    void doesNotFireOnUnrelatedText() {
        assertFalse(router.shouldExposeTool("fetch", "renomeia essa variavel no metodo"));
        assertFalse(router.shouldExposeTool("fetch", "roda os testes do projeto"));
    }

    @Test
    void identicalStringsAreWithinOneEdit() {
        assertThat(IntentRouter.withinOneEdit("pesquisa", "pesquisa")).isTrue();
    }

    @Test
    void detectsSubstitutionInsertionAndDeletion() {
        assertThat(IntentRouter.withinOneEdit("pesquisr", "pesquisa")).isTrue(); // substituicao
        assertThat(IntentRouter.withinOneEdit("pesquis", "pesquisa")).isTrue(); // falta uma
        assertThat(IntentRouter.withinOneEdit("pesquisaa", "pesquisa")).isTrue(); // sobra uma
    }

    @Test
    void rejectsTwoOrMoreEdits() {
        assertThat(IntentRouter.withinOneEdit("pesqsr", "pesquisa")).isFalse();
        assertThat(IntentRouter.withinOneEdit("comando", "pesquisa")).isFalse();
    }
}
