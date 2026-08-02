package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Indexing a project used to hand the whole thing to the vector store in one {@code add}, which asks
 * the embedding model for thousands of vectors in a single call. On a 16 GB machine that burst
 * competes with the chat model for RAM — and the chat is what the user is waiting on.
 */
class RagServiceBatchingTest {

    @TempDir
    Path tempDir;

    @Test
    void sendsChunksToTheVectorStoreInBoundedBatches() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        RagService ragService = ragServiceWithBatchSize(vectorStore, 2);
        Path project = projectWithFiles(5);

        ragService.indexProject(List.of(project.toString()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> batches = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, org.mockito.Mockito.times(3)).add(batches.capture());
        assertThat(batches.getAllValues()).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(2));
        assertThat(batches.getAllValues().stream().mapToInt(List::size).sum()).isEqualTo(5);
    }

    /** Nothing to index must not turn into an empty call to the embedding model. */
    @Test
    void doesNotTouchTheVectorStoreWhenThereIsNothingNew() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        RagService ragService = ragServiceWithBatchSize(vectorStore, 2);
        Path empty = Files.createDirectory(tempDir.resolve("vazio"));

        ragService.indexProject(List.of(empty.toString()));

        verify(vectorStore, org.mockito.Mockito.never()).add(org.mockito.ArgumentMatchers.anyList());
    }

    private RagService ragServiceWithBatchSize(VectorStore vectorStore, int batchSize) {
        // A bare Redis mock is enough: every manifest and cache access in RagService already degrades
        // to "no manifest" when Redis does not answer, which is exactly a first indexing.
        return new RagService(vectorStore, mock(StringRedisTemplate.class), new ObjectMapper(), 0.62, 30, 5, batchSize);
    }

    private Path projectWithFiles(int count) throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("projeto"));
        for (int index = 0; index < count; index++) {
            Files.writeString(
                    project.resolve("Classe" + index + ".java"),
                    "package loja;\n\npublic class Classe" + index + " {\n"
                            + "    // texto suficiente para virar um chunk de verdade no splitter,\n"
                            + "    // que descarta trechos curtos demais para valer um embedding.\n"
                            + "    void metodo" + index + "() { System.out.println(\"classe " + index + "\"); }\n"
                            + "}\n");
        }
        return project;
    }
}
