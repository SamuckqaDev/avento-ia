package com.avento.service.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.service.RagService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

/**
 * {@code search_code} used to reach only the literal matcher, while the vector index sat behind a
 * REST controller nobody called. This service is the junction: vector when the index answers,
 * literal while it does not.
 */
class CodeSearchServiceTest {

    @TempDir
    Path tempDir;

    private RagService ragService;
    private WorkspaceIndexingService indexingService;
    private CodeSearchService codeSearchService;
    private Path project;

    @BeforeEach
    void setUp() throws Exception {
        ragService = mock(RagService.class);
        indexingService = mock(WorkspaceIndexingService.class);
        codeSearchService = new CodeSearchService(ragService, new CodebaseRagService(), indexingService);

        project = Files.createDirectory(tempDir.resolve("projeto"));
        Files.createDirectory(project.resolve("src"));
        Files.writeString(
                project.resolve("src/Pagamento.java"),
                """
                package loja;

                public class Pagamento {
                    void autorizarCobranca() {}
                }
                """);
    }

    @Test
    void usesTheVectorIndexWhenItIsReady() {
        Path file = project.resolve("src/Pagamento.java");
        indexIsReady();
        when(ragService.searchContext(anyString(), anyList()))
                .thenReturn(List.of(chunk(file, "public class Pagamento {")));

        CodeSearchService.Result result = codeSearchService.search(project, "onde autorizo um pagamento", 5);

        assertThat(result.strategy()).isEqualTo(CodeSearchService.Strategy.VECTOR);
        assertThat(result.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.filePath()).isEqualTo(file.toString());
            assertThat(hit.snippet()).contains("class Pagamento");
        });
    }

    /**
     * The first search of a project happens before the index exists. Answering nothing there would
     * make the tool useless exactly when the agent is getting its bearings.
     */
    @Test
    void fallsBackToLiteralMatchingWhileTheIndexIsCold() {
        when(indexingService.indexedRootFor(any())).thenReturn(Optional.empty());

        CodeSearchService.Result result = codeSearchService.search(project, "autorizarCobranca", 5);

        assertThat(result.strategy()).isEqualTo(CodeSearchService.Strategy.LITERAL);
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits().get(0).filePath()).endsWith("Pagamento.java");
    }

    /**
     * An empty vector result is not proof of absence: the 0.62 similarity threshold was calibrated on
     * prose, and code embeds differently. Literal matching gets a turn before giving up.
     */
    @Test
    void fallsBackToLiteralWhenTheVectorSearchFindsNothing() {
        indexIsReady();
        when(ragService.searchContext(anyString(), anyList())).thenReturn(List.of());

        CodeSearchService.Result result = codeSearchService.search(project, "autorizarCobranca", 5);

        assertThat(result.strategy()).isEqualTo(CodeSearchService.Strategy.LITERAL);
        assertThat(result.hits()).isNotEmpty();
    }

    /** Redis down, embedding model down: the tool degrades, it does not fail the round. */
    @Test
    void fallsBackToLiteralWhenTheVectorSearchBlowsUp() {
        indexIsReady();
        when(ragService.searchContext(anyString(), anyList())).thenThrow(new IllegalStateException("redis down"));

        CodeSearchService.Result result = codeSearchService.search(project, "autorizarCobranca", 5);

        assertThat(result.strategy()).isEqualTo(CodeSearchService.Strategy.LITERAL);
        assertThat(result.hits()).isNotEmpty();
    }

    /**
     * The index is keyed by project root, but the model routinely searches a subfolder. Hits from the
     * rest of the project must not leak into an answer that was asked about one folder.
     */
    @Test
    void keepsOnlyHitsUnderTheFolderThatWasSearched() throws Exception {
        Path other = Files.createDirectory(project.resolve("docs"));
        Path outsideHit = Files.writeString(other.resolve("notas.md"), "cobranca autorizada manualmente");
        Path insideHit = project.resolve("src/Pagamento.java");
        when(indexingService.indexedRootFor(any())).thenReturn(Optional.of(project));
        when(indexingService.isReady(project)).thenReturn(true);
        when(ragService.searchContext(anyString(), anyList()))
                .thenReturn(List.of(chunk(outsideHit, "cobranca autorizada"), chunk(insideHit, "class Pagamento")));

        CodeSearchService.Result result = codeSearchService.search(project.resolve("src"), "cobranca", 5);

        assertThat(result.strategy()).isEqualTo(CodeSearchService.Strategy.VECTOR);
        assertThat(result.hits()).singleElement().satisfies(hit -> assertThat(hit.filePath())
                .endsWith("Pagamento.java"));
    }

    /** A hit the user cannot open is half an answer, so the chunk is located back in its file. */
    @Test
    void resolvesTheLineWhereTheChunkStarts() {
        Path file = project.resolve("src/Pagamento.java");
        indexIsReady();
        when(ragService.searchContext(anyString(), anyList()))
                .thenReturn(List.of(chunk(file, "public class Pagamento {")));

        CodeSearchService.Result result = codeSearchService.search(project, "pagamento", 5);

        assertThat(result.hits().get(0).startLine()).isEqualTo(3);
    }

    private void indexIsReady() {
        when(indexingService.indexedRootFor(any())).thenReturn(Optional.of(project));
        when(indexingService.isReady(project)).thenReturn(true);
    }

    private Document chunk(Path file, String text) {
        return new Document(
                "chunk-" + file.getFileName() + "-" + text.hashCode(),
                text,
                Map.of("source", file.toString(), "projectRoot", project.toString()));
    }
}
