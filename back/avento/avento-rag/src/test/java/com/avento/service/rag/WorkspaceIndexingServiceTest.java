package com.avento.service.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.avento.service.RagService;
import com.avento.service.event.WorkspaceRootRegisteredEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The indexer is what closes the gap between the two search paths: {@code RagService} held real
 * vector search but only a REST controller ever called it, so the index the agent searched was
 * always empty.
 */
class WorkspaceIndexingServiceTest {

    private static final long AWAIT_MILLIS = 5_000;

    @TempDir
    Path tempDir;

    @Test
    void indexesAProjectWhenItsWorkspaceIsRegistered() throws Exception {
        RagService ragService = mock(RagService.class);
        WorkspaceIndexingService indexer = new WorkspaceIndexingService(ragService, true, 0, "");
        Path project = Files.createDirectory(tempDir.resolve("projeto"));

        indexer.onWorkspaceRegistered(new WorkspaceRootRegisteredEvent(project, null));

        verify(ragService, timeout(AWAIT_MILLIS)).indexProject(List.of(project.toString()));
        assertThat(awaitReady(indexer, project)).isTrue();
    }

    /** Two conversations on the same project must not pay for the first pass twice. */
    @Test
    void indexesTheSameProjectOnlyOnce() throws Exception {
        RagService ragService = mock(RagService.class);
        WorkspaceIndexingService indexer = new WorkspaceIndexingService(ragService, true, 0, "");
        Path project = Files.createDirectory(tempDir.resolve("projeto"));

        indexer.requestIndexing(project);
        assertThat(awaitReady(indexer, project)).isTrue();
        indexer.requestIndexing(project);
        indexer.requestIndexing(project);

        verify(ragService, timeout(AWAIT_MILLIS)).indexProject(List.of(project.toString()));
    }

    /**
     * The embedding model being down is the common failure, and it must not take the tool with it —
     * {@code search_code} still answers by literal matching while the index is broken.
     */
    @Test
    void reportsFailureInsteadOfPretendingTheIndexIsReady() throws Exception {
        RagService ragService = mock(RagService.class);
        doThrow(new IllegalStateException("ollama offline")).when(ragService).indexProject(anyList());
        WorkspaceIndexingService indexer = new WorkspaceIndexingService(ragService, true, 0, "");
        Path project = Files.createDirectory(tempDir.resolve("projeto"));

        indexer.requestIndexing(project);

        verify(ragService, timeout(AWAIT_MILLIS)).indexProject(anyList());
        assertThat(awaitState(indexer, project, WorkspaceIndexingService.IndexState.FAILED))
                .isTrue();
        assertThat(indexer.isReady(project)).isFalse();
    }

    @Test
    void reindexesTheProjectWhenOneOfItsFilesIsSaved() throws Exception {
        RagService ragService = mock(RagService.class);
        WorkspaceIndexingService indexer = new WorkspaceIndexingService(ragService, true, 0, "");
        Path project = Files.createDirectory(tempDir.resolve("projeto"));
        Files.createDirectory(project.resolve("src"));
        Path file = Files.writeString(project.resolve("src/App.java"), "class App {}");

        indexer.requestIndexing(project);
        assertThat(awaitReady(indexer, project)).isTrue();

        indexer.noteFileChanged(file);

        verify(ragService, timeout(AWAIT_MILLIS).times(2)).indexProject(List.of(project.toString()));
    }

    /** A file nobody registered a project for has no index to refresh. */
    @Test
    void ignoresChangesOutsideAnyIndexedProject() throws Exception {
        RagService ragService = mock(RagService.class);
        WorkspaceIndexingService indexer = new WorkspaceIndexingService(ragService, true, 0, "");
        Path loose = Files.writeString(tempDir.resolve("solto.java"), "class Solto {}");

        indexer.noteFileChanged(loose);

        Thread.sleep(200);
        verify(ragService, never()).indexProject(anyList());
    }

    @Test
    void doesNothingWhenAutoIndexingIsOff() throws Exception {
        RagService ragService = mock(RagService.class);
        WorkspaceIndexingService indexer = new WorkspaceIndexingService(ragService, false, 0, "");
        Path project = Files.createDirectory(tempDir.resolve("projeto"));

        indexer.onWorkspaceRegistered(new WorkspaceRootRegisteredEvent(project, null));

        Thread.sleep(200);
        verify(ragService, never()).indexProject(anyList());
        assertThat(indexer.stateOf(project)).isEqualTo(WorkspaceIndexingService.IndexState.UNKNOWN);
    }

    /** With a project checked out inside another, a file belongs to the closest root, not the outer one. */
    @Test
    void attributesAFileToTheDeepestProjectThatContainsIt() throws Exception {
        RagService ragService = mock(RagService.class);
        WorkspaceIndexingService indexer = new WorkspaceIndexingService(ragService, true, 0, "");
        Path outer = Files.createDirectory(tempDir.resolve("fora"));
        Path inner = Files.createDirectory(outer.resolve("dentro"));

        indexer.requestIndexing(outer);
        indexer.requestIndexing(inner);

        assertThat(indexer.indexedRootFor(inner.resolve("App.java"))).contains(inner);
        assertThat(indexer.indexedRootFor(outer.resolve("App.java"))).contains(outer);
    }

    private boolean awaitReady(WorkspaceIndexingService indexer, Path project) throws InterruptedException {
        return awaitState(indexer, project, WorkspaceIndexingService.IndexState.READY);
    }

    private boolean awaitState(
            WorkspaceIndexingService indexer, Path project, WorkspaceIndexingService.IndexState expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (indexer.stateOf(project) == expected) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    /**
     * Autorizar acesso e querer busca vetorial sao intencoes diferentes, e a indexacao por evento as
     * acoplou: o endpoint que libera a home inteira com um clique passou a mandar indexa-la. Numa
     * subida real isso encheu o indice com 9.468 chunks — a amostra apontou 226 em 250 vindos da
     * pasta pessoal — para um projeto de 97 arquivos.
     */
    @Test
    void naoIndexaAPastaPessoalInteira() {
        RagService ragService = mock(RagService.class);
        WorkspaceIndexingService service = new WorkspaceIndexingService(ragService, true, 0, "");

        service.requestIndexing(Path.of(System.getProperty("user.home")));

        verify(ragService, never()).indexProject(org.mockito.ArgumentMatchers.anyList());
        assertThat(service.stateOf(Path.of(System.getProperty("user.home"))))
                .isEqualTo(WorkspaceIndexingService.IndexState.UNKNOWN);
    }

    /** A pasta que CONTEM os projetos tambem nao e um projeto. */
    @Test
    void naoIndexaAPastaQueContemOsProjetos() {
        RagService ragService = mock(RagService.class);
        String paiDosProjetos = tempDir.toString();
        WorkspaceIndexingService service = new WorkspaceIndexingService(ragService, true, 0, paiDosProjetos);

        service.requestIndexing(tempDir);

        verify(ragService, never()).indexProject(org.mockito.ArgumentMatchers.anyList());
    }
}
