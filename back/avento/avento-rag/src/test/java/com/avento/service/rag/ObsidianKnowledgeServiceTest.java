package com.avento.service.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.dto.ObsidianVaultStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

class ObsidianKnowledgeServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void initializesOnlyMissingStarterFilesAndQueuesAnIndex() throws Exception {
        RagService ragService = mock(RagService.class);
        WorkspaceIndexingService indexingService = mock(WorkspaceIndexingService.class);
        Path vault = tempDir.resolve("vault");
        when(indexingService.requestReindexing(vault.toAbsolutePath().normalize())).thenReturn(true);
        when(indexingService.stateOf(vault.toAbsolutePath().normalize()))
                .thenReturn(WorkspaceIndexingService.IndexState.INDEXING);
        ObsidianKnowledgeService service = new ObsidianKnowledgeService(ragService, indexingService, vault.toString());

        ObsidianVaultStatus status = service.initialize();

        assertThat(status.available()).isTrue();
        assertThat(status.indexState()).isEqualTo("INDEXING");
        assertThat(Files.readString(vault.resolve("README.md"))).contains("Avento Knowledge Vault");
        assertThat(Files.readString(vault.resolve("40-Policies/README.md"))).contains("not executable");
        verify(indexingService).requestReindexing(vault.toAbsolutePath().normalize());
    }

    @Test
    void searchesOnlyWithinTheConfiguredVault() throws Exception {
        RagService ragService = mock(RagService.class);
        WorkspaceIndexingService indexingService = mock(WorkspaceIndexingService.class);
        Path vault = Files.createDirectory(tempDir.resolve("vault"));
        Document note = new Document("note-1", "Avento usa Redis para o RAG", Map.of());
        when(ragService.searchContext("como funciona o rag", List.of(vault.toAbsolutePath().normalize().toString())))
                .thenReturn(List.of(note));
        ObsidianKnowledgeService service = new ObsidianKnowledgeService(ragService, indexingService, vault.toString());

        List<Document> result = service.search("como funciona o rag");

        assertThat(result).containsExactly(note);
        verify(ragService).searchContext("como funciona o rag", List.of(vault.toAbsolutePath().normalize().toString()));
    }

    @Test
    void savesDetailedKnowledgeInTheRequestedVaultAreaAndQueuesReindexing() throws Exception {
        RagService ragService = mock(RagService.class);
        WorkspaceIndexingService indexingService = mock(WorkspaceIndexingService.class);
        Path vault = tempDir.resolve("vault");
        when(indexingService.requestReindexing(vault.toAbsolutePath().normalize())).thenReturn(true);
        ObsidianKnowledgeService service = new ObsidianKnowledgeService(ragService, indexingService, vault.toString());

        var note = service.saveNote(
                "Arquitetura em camadas do Avento",
                "Controllers ficam finos, serviços coordenam os casos de uso e DTOs protegem as fronteiras.",
                "project");

        assertThat(note.indexQueued()).isTrue();
        assertThat(Files.isRegularFile(Path.of(note.path()))).isTrue();
        assertThat(Path.of(note.path()).getParent()).isEqualTo(vault.resolve("20-Projects"));
        assertThat(Files.readString(Path.of(note.path()))).contains("# Arquitetura em camadas do Avento");
        verify(indexingService).requestReindexing(vault.toAbsolutePath().normalize());
    }

    @Test
    void returnsNothingBeforeTheVaultExists() {
        RagService ragService = mock(RagService.class);
        WorkspaceIndexingService indexingService = mock(WorkspaceIndexingService.class);
        ObsidianKnowledgeService service = new ObsidianKnowledgeService(
                ragService, indexingService, tempDir.resolve("missing").toString());

        assertThat(service.search("qualquer pergunta")).isEmpty();
        verify(ragService, never()).searchContext(any(), anyList());
    }
}
