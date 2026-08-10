package com.avento.service.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

/**
 * A recuperação do Avento passou a falar {@code DocumentRetriever}. Estes testes travam o que a
 * adoção do contrato NÃO pode custar.
 */
class WorkspaceDocumentRetrieverTest {

    private final CodeSearchService codeSearch = mock(CodeSearchService.class);
    private final WorkspaceDocumentRetriever retriever = new WorkspaceDocumentRetriever(codeSearch);

    private CodeSearchService.Result resultWith(CodeSearchService.Strategy strategy, String file) {
        return new CodeSearchService.Result(
                strategy, List.of(new CodeSearchService.Hit(file, 10, 20, "trecho de codigo", 0.71)));
    }

    private Query queryFor(String text, String path) {
        return Query.builder()
                .text(text)
                .context(Map.of(WorkspaceDocumentRetriever.CONTEXT_PATH, path))
                .build();
    }

    /**
     * <b>O teste que justifica a classe existir.</b> O {@code ConcatenationDocumentJoiner} do Spring
     * AI descarta de qual recuperador cada documento veio. Aqui isso importa: sem saber se a resposta
     * foi vetorial ou literal, o modelo não distingue "não existe" de "não bate literalmente" — e já
     * respondeu com confiança errada por causa dessa lacuna.
     */
    @Test
    void carriesTheStrategyInTheMetadataSoTheCallerCanTellVectorFromLiteral() {
        when(codeSearch.search(any(), eq("onde valido o path"), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(resultWith(CodeSearchService.Strategy.LITERAL, "/tmp/p/A.java"));

        List<Document> documents = retriever.retrieve(queryFor("onde valido o path", "/tmp/p"));

        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst().getMetadata())
                .containsEntry(WorkspaceDocumentRetriever.METADATA_STRATEGY, "LITERAL");
    }

    @Test
    void reportsTheVectorStrategyWhenTheIndexAnswered() {
        when(codeSearch.search(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(resultWith(CodeSearchService.Strategy.VECTOR, "/tmp/p/B.java"));

        List<Document> documents = retriever.retrieve(queryFor("busca semantica", "/tmp/p"));

        assertThat(documents.getFirst().getMetadata())
                .containsEntry(WorkspaceDocumentRetriever.METADATA_STRATEGY, "VECTOR");
    }

    /**
     * Sem diretório não há busca. O índice é por raiz de projeto; uma busca sem raiz varreria tudo, e
     * o custo disso cai no usuário sem ele ter pedido.
     */
    @Test
    void refusesToSearchWhenNoDirectoryWasGiven() {
        List<Document> documents = retriever.retrieve(new Query("onde valido o path"));

        assertThat(documents).isEmpty();
        verify(codeSearch, never()).search(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    /**
     * Texto em branco não chega aqui: o próprio {@code Query.Builder} do Spring AI recusa com
     * {@code "text cannot be null or empty"}. Descobri isso escrevendo o teste — ele não compunha o
     * cenário. A guarda de texto em branco no retriever fica como cinto e suspensório para quem
     * construir o Query por outro caminho; o que dá para exercitar aqui é o argumento nulo.
     */
    @Test
    void returnsNothingWhenThereIsNoQueryAtAll() {
        List<Document> documents = retriever.retrieve(null);

        assertThat(documents).isEmpty();
        verify(codeSearch, never()).search(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    /**
     * As linhas vão para metadados, não para o texto.
     *
     * <p>O texto é o que chega ao modelo; número de linha embutido nele vira ruído que o modelo às
     * vezes copia para dentro do código que escreve.
     */
    @Test
    void keepsLineNumbersOutOfTheTextAndInTheMetadata() {
        when(codeSearch.search(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(resultWith(CodeSearchService.Strategy.VECTOR, "/tmp/p/C.java"));

        Document document = retriever.retrieve(queryFor("q", "/tmp/p")).getFirst();

        assertThat(document.getText()).isEqualTo("trecho de codigo").doesNotContain("10");
        assertThat(document.getMetadata())
                .containsEntry("startLine", 10)
                .containsEntry("endLine", 20)
                .containsEntry("source", "/tmp/p/C.java");
    }

    /** O teto vem do contexto quando informado — quem chama sabe quanto contexto cabe na rodada. */
    @Test
    void honoursTheMaxResultsFromTheQueryContext() {
        when(codeSearch.search(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(resultWith(CodeSearchService.Strategy.VECTOR, "/tmp/p/D.java"));

        Query query = Query.builder()
                .text("q")
                .context(Map.of(
                        WorkspaceDocumentRetriever.CONTEXT_PATH,
                        "/tmp/p",
                        WorkspaceDocumentRetriever.CONTEXT_MAX_RESULTS,
                        3))
                .build();

        retriever.retrieve(query);

        verify(codeSearch).search(any(Path.class), eq("q"), eq(3));
    }

    /** É um {@code Function<Query, List<Document>>}: qualquer componente do Spring AI pode compô-lo. */
    @Test
    void behavesAsTheFunctionTheSpringAiContractPromises() {
        when(codeSearch.search(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(resultWith(CodeSearchService.Strategy.VECTOR, "/tmp/p/E.java"));

        List<Document> documents = retriever.apply(queryFor("q", "/tmp/p"));

        assertThat(documents).hasSize(1);
    }
}
