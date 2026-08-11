package com.avento.service.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * One root owns ONE manifest, whatever the index is called. Keying the manifest by index name is
     * what stranded 5.240 chunks in Redis on 10/08/2026: the old manifest sat at an address nobody
     * computed anymore, so nothing could find the chunks it listed in order to delete them.
     */
    @Test
    void usesTheSameManifestKeyRegardlessOfTheVectorIndexName() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("mesmo-projeto"));

        String nomicManifestKey = manifestKeyReadWhenIndexing(project, "avento_index_nomic_embed_text");
        String bgeManifestKey = manifestKeyReadWhenIndexing(project, "avento_index_bge_m3");

        assertThat(nomicManifestKey).startsWith("avento:rag:manifest:");
        assertThat(nomicManifestKey).isEqualTo(bgeManifestKey);
    }

    /**
     * The whole point of the index name living inside the manifest: when it changes, the chunks of the
     * previous index are deleted in the same pass that writes the new ones. Otherwise they stay behind
     * as unreachable duplicates, indexed by whatever index still covers the key prefix.
     */
    @Test
    void deletesTheChunksOfThePreviousIndexWhenTheIndexNameChanges() throws Exception {
        Path project = projectWithFiles(3);
        Map<String, String> redisValues = new HashMap<>();
        StringRedisTemplate redis = redisBackedBy(redisValues);

        VectorStore oldStore = mock(VectorStore.class);
        new RagService(oldStore, redis, new ObjectMapper(), "avento_index_nomic_embed_text", 0.45, 30, 5, 50)
                .indexProject(List.of(project.toString()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> firstPass = ArgumentCaptor.forClass(List.class);
        verify(oldStore).add(firstPass.capture());
        List<String> chunkIdsOfTheOldIndex =
                firstPass.getValue().stream().map(Document::getId).toList();
        assertThat(chunkIdsOfTheOldIndex).hasSize(3);

        VectorStore newStore = mock(VectorStore.class);
        new RagService(newStore, redis, new ObjectMapper(), "avento_index_bge_m3", 0.45, 30, 5, 50)
                .indexProject(List.of(project.toString()));

        // The stale ids are deleted...
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> deleted = ArgumentCaptor.forClass(List.class);
        verify(newStore, org.mockito.Mockito.atLeastOnce()).delete(deleted.capture());
        assertThat(deleted.getAllValues().stream().flatMap(List::stream).toList())
                .containsAll(chunkIdsOfTheOldIndex);

        // ...and the files are embedded again, rather than skipped as unchanged.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> added = ArgumentCaptor.forClass(List.class);
        verify(newStore, org.mockito.Mockito.atLeastOnce()).add(added.capture());
        assertThat(added.getAllValues().stream().flatMap(List::stream).toList()).hasSize(3);
    }

    /**
     * A manifest written before the index name was recorded deserializes with {@code indexName == null}.
     * That must read as "different index" so the stale chunks are cleaned up, not as "same index" — the
     * upgrade path is the one case where getting this wrong leaves garbage behind for good.
     */
    @Test
    void treatsAManifestWithoutAnIndexNameAsBelongingToAnotherIndex() throws Exception {
        Path project = projectWithFiles(2);
        Map<String, String> redisValues = new HashMap<>();
        StringRedisTemplate redis = redisBackedBy(redisValues);

        // Index once so the manifest carries the REAL content hashes, then strip the indexName field to
        // get exactly the shape the old code wrote. The hashes must stay correct: with a bogus hash the
        // per-file mismatch path would delete the chunks on its own, and this test would pass without
        // the index-change path ever running. Verified by inverting the implementation — see the comment
        // on deletesTheChunksOfThePreviousIndexWhenTheIndexNameChanges.
        VectorStore firstPass = mock(VectorStore.class);
        new RagService(firstPass, redis, new ObjectMapper(), "avento_index_nomic_embed_text", 0.45, 30, 5, 50)
                .indexProject(List.of(project.toString()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> written = ArgumentCaptor.forClass(List.class);
        verify(firstPass).add(written.capture());
        List<String> chunkIdsWrittenBefore =
                written.getValue().stream().map(Document::getId).toList();
        assertThat(chunkIdsWrittenBefore).hasSize(2);

        String manifestKey = manifestKeyFor(project);
        String legacyManifest = redisValues.get(manifestKey).replace("\"indexName\":\"avento_index_nomic_embed_text\",", "");
        assertThat(legacyManifest).doesNotContain("indexName");
        redisValues.put(manifestKey, legacyManifest);

        // Same index name as the first pass: without the null-means-different rule, this pass would find
        // every hash matching and skip the whole project, leaving those chunks stranded for good.
        VectorStore store = mock(VectorStore.class);
        new RagService(store, redis, new ObjectMapper(), "avento_index_nomic_embed_text", 0.45, 30, 5, 50)
                .indexProject(List.of(project.toString()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> deleted = ArgumentCaptor.forClass(List.class);
        verify(store, org.mockito.Mockito.atLeastOnce()).delete(deleted.capture());
        assertThat(deleted.getAllValues().stream().flatMap(List::stream).toList())
                .containsAll(chunkIdsWrittenBefore);
    }

    @Test
    void reindexesWhenACompleteManifestBelongsToThePreviousVectorIndex() throws Exception {
        Path project = projectWithFiles(2);
        Map<String, String> redisValues = new HashMap<>();
        StringRedisTemplate redis = redisBackedBy(redisValues);
        VectorStore oldVectorStore = mock(VectorStore.class);

        new RagService(
                        oldVectorStore,
                        redis,
                        new ObjectMapper(),
                        "avento_index_nomic_embed_text",
                        0.45,
                        30,
                        5,
                        2)
                .indexProject(List.of(project.toString()));

        assertThat(redisValues).hasSize(1);

        VectorStore newVectorStore = mock(VectorStore.class);
        new RagService(newVectorStore, redis, new ObjectMapper(), "avento_index_bge_m3", 0.45, 30, 5, 2)
                .indexProject(List.of(project.toString()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> documentsToAdd = ArgumentCaptor.forClass(List.class);
        verify(newVectorStore, org.mockito.Mockito.atLeastOnce()).add(documentsToAdd.capture());
        assertThat(documentsToAdd.getAllValues().stream().flatMap(List::stream).toList()).isNotEmpty();
    }

    private RagService ragServiceWithBatchSize(VectorStore vectorStore, int batchSize) {
        // A bare Redis mock is enough: every manifest and cache access in RagService already degrades
        // to "no manifest" when Redis does not answer, which is exactly a first indexing.
        return new RagService(
                vectorStore, mock(StringRedisTemplate.class), new ObjectMapper(), "avento_index_nomic_embed_text", 0.45, 30, 5, batchSize);
    }

    /** Same derivation the service uses: the manifest belongs to the root, not to the index. */
    private String manifestKeyFor(Path project) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(project.toAbsolutePath()
                        .normalize()
                        .toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte item : digest) {
            hex.append(String.format("%02x", item));
        }
        return "avento:rag:manifest:" + hex;
    }

    private String manifestKeyReadWhenIndexing(Path project, String indexName) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> values =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(redis.opsForValue()).thenReturn(values);

        new RagService(mock(VectorStore.class), redis, new ObjectMapper(), indexName, 0.45, 30, 5, 2)
                .indexProject(List.of(project.toString()));

        ArgumentCaptor<String> manifestKey = ArgumentCaptor.forClass(String.class);
        verify(values).get(manifestKey.capture());
        return manifestKey.getValue();
    }

    private StringRedisTemplate redisBackedBy(Map<String, String> valuesByKey) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> values =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(redis.opsForValue()).thenReturn(values);
        org.mockito.Mockito.when(values.get(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> valuesByKey.get(invocation.getArgument(0)));
        org.mockito.Mockito.doAnswer(invocation -> {
                    valuesByKey.put(invocation.getArgument(0), invocation.getArgument(1));
                    return null;
                })
                .when(values)
                .set(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        return redis;
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

        RagService ragService = new RagService(
                vectorStore, redis, new ObjectMapper(), "avento_index_nomic_embed_text", 0.45, 30, 5, 2);

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
