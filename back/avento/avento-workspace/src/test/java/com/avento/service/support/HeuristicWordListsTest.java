package com.avento.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Cobre o carregamento das listas de heurística, com atenção especial às aspas.
 *
 * <p>O {@code strip()} de cada linha é o que permite indentar o arquivo, mas comeria os espaços de
 * termos como {@code " ui "} — que existem justamente por causa da borda: sem os espaços, {@code ui}
 * casaria dentro de "build", "gui" e "quiz". As aspas marcam "este espaço é significativo", e é essa
 * distinção que os testes abaixo travam.
 */
class HeuristicWordListsTest {

    private static final String RESOURCE = "heuristics-test/quoted-terms.txt";

    @Test
    void keepsTheSpacesInsideQuotedTerms() {
        Map<String, List<String>> sections = HeuristicWordLists.loadSections(RESOURCE);

        assertThat(sections.get("BOUNDED")).containsExactly(" ui ", " app ");
    }

    @Test
    void leavesUnquotedTermsUntouched() {
        Map<String, List<String>> sections = HeuristicWordLists.loadSections(RESOURCE);

        assertThat(sections.get("PLAIN")).containsExactly("dashboard", "pagina web");
    }

    @Test
    void skipsCommentsAndBlankLines() {
        List<String> lines = HeuristicWordLists.loadLines(RESOURCE);

        assertThat(lines).doesNotContain("# Comentário deve ser ignorado.").isNotEmpty();
    }

    @Test
    void unquotesInFlatListsToo() {
        // loadLines e loadSections passam pelo mesmo unquote; um caminho corrigido e o outro não
        // seria a pior versão do bug, porque só apareceria no arquivo que usa o outro carregador.
        List<String> lines = HeuristicWordLists.loadLines(RESOURCE);

        assertThat(lines).contains(" ui ", " app ", "dashboard");
    }
}
