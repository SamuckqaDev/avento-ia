package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.avento.service.rag.VectorStoreResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

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
        VectorStoreResolver resolver = mock(VectorStoreResolver.class);
        org.mockito.Mockito.when(resolver.active()).thenReturn(vectorStore);
        org.mockito.Mockito.when(resolver.activeIndexName()).thenReturn("avento_index");
        // A bare Redis mock is enough: every manifest and cache access in RagService already degrades
        // to "no manifest" when Redis does not answer, which is exactly a first indexing.
        return new RagService(resolver, mock(StringRedisTemplate.class), new ObjectMapper(), 0.62, 30, 5, batchSize);
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

    /**
     * A remocao ia inteira num pipeline so. O Redis e single-thread: num indice de 39 mil documentos
     * isso o segura tempo suficiente para OUTROS clientes estourarem o timeout de conexao — em
     * producao quem apareceu no log foi a leitura de preferencias do usuario, que nao tem nada a ver
     * com RAG. O erro apontava para a vitima, nao para a causa.
     */
    @Test
    void removeChunksEmLotesTambem() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        // Um manifesto com sete chunks antigos: sem ele o servico nao tem o que remover, porque a
        // memoria do que ja foi indexado vive no Redis.
        String manifesto = "{\"projectRoot\":\"/tmp/projeto\",\"files\":{\"Velho.java\":"
                + "{\"fileHash\":\"h\",\"chunkIds\":[\"c1\",\"c2\",\"c3\",\"c4\",\"c5\",\"c6\",\"c7\"]}}}";
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> valores =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(redis.opsForValue()).thenReturn(valores);
        org.mockito.Mockito.when(valores.get(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(manifesto);

        VectorStoreResolver resolver = mock(VectorStoreResolver.class);
        org.mockito.Mockito.when(resolver.active()).thenReturn(vectorStore);
        org.mockito.Mockito.when(resolver.activeIndexName()).thenReturn("avento_index");
        RagService ragService = new RagService(resolver, redis, new ObjectMapper(), 0.62, 30, 5, 2);

        ragService.clearProjects(
                List.of(Files.createDirectory(tempDir.resolve("limpar")).toString()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> remocoes = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, org.mockito.Mockito.atLeastOnce()).delete(remocoes.capture());
        // Sete ids, lote de dois: quatro chamadas, nenhuma maior que o lote.
        assertThat(remocoes.getAllValues()).allSatisfy(lote -> assertThat(lote).hasSizeLessThanOrEqualTo(2));
        assertThat(remocoes.getAllValues().stream().mapToInt(List::size).sum()).isEqualTo(7);
    }
}
