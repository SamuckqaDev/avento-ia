package com.avento.service.rag;

import com.avento.dto.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class RagService {

    private static final Logger logger = LoggerFactory.getLogger(RagService.class);
    private static final int MAX_FILES_TO_SCAN = 12000;
    // Mude junto com o CodeAwareSplitter: e o que forca a reindexacao de tudo.
    private static final String CHUNK_STRATEGY = "member-v1";
    private static final Duration SEARCH_CACHE_TTL = Duration.ofMinutes(10);
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git",
            "node_modules",
            ".expo",
            "dist",
            "build",
            "target",
            "ios",
            "android",
            ".next",
            ".venv",
            "venv",
            ".idea",
            ".gradle",
            "coverage",
            ".dart_tool",
            "tmp",
            "logs",
            "piper_tts",
            "whisper.cpp");

    // Resolvido a cada uso, não guardado: trocar o modelo de embedding nas configurações troca o
    // índice, e um store fixado no construtor continuaria gravando no índice do modelo antigo.
    private final VectorStoreResolver vectorStoreResolver;
    private final TokenTextSplitter textSplitter;
    private final CodeAwareSplitter codeSplitter;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper mapper;
    private final double similarityThreshold;
    private final int searchCandidateLimit;
    private final int searchResultLimit;
    private final int embeddingBatchSize;

    public RagService(
            VectorStoreResolver vectorStoreResolver,
            StringRedisTemplate redisTemplate,
            ObjectMapper mapper,
            // 0.45, e nao 0.62: o valor antigo foi herdado de prosa e MEDIDO como errado para codigo —
            // descartava a resposta certa em metade das buscas, inclusive com o nome exato de um metodo
            // escrito no arquivo. O application.yml ja dizia 0.45; este default dizia 0.62, entao
            // qualquer contexto sem aquele yml (teste, outro profile) silenciosamente usava o valor
            // ruim. O default do codigo tem de carregar a medicao, nao o folclore.
            @Value("${avento.rag.similarity-threshold:0.45}") double similarityThreshold,
            @Value("${avento.rag.candidate-limit:30}") int searchCandidateLimit,
            @Value("${avento.rag.result-limit:5}") int searchResultLimit,
            @Value("${avento.rag.embedding-batch-size:32}") int embeddingBatchSize) {
        this.vectorStoreResolver = vectorStoreResolver;
        this.redisTemplate = redisTemplate;
        this.mapper = mapper;
        this.similarityThreshold = Math.max(0.0, Math.min(1.0, similarityThreshold));
        this.searchCandidateLimit = Math.max(1, searchCandidateLimit);
        this.searchResultLimit = Math.max(1, Math.min(this.searchCandidateLimit, searchResultLimit));
        this.embeddingBatchSize = Math.max(1, embeddingBatchSize);
        // Teto de 6000 chars: so parte membro que sozinho estouraria a janela do embedding.
        // Piso de 120: abaixo disso o membro cola no anterior. Ver a medicao no CodeAwareSplitter.
        this.codeSplitter = new CodeAwareSplitter(6000, 120);
        this.textSplitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .withMinChunkSizeChars(100)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build();
    }

    public void indexProject(List<String> projectPaths) {
        for (String path : projectPaths == null ? List.<String>of() : projectPaths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            indexSingleProject(Paths.get(path).toAbsolutePath().normalize());
        }
    }

    public void clearProjects(List<String> projectPaths) {
        for (String path : projectPaths == null ? List.<String>of() : projectPaths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            String projectKey = projectKey(Paths.get(path));
            Manifest manifest = readManifest(projectKey);
            deleteChunks(manifest.files().values().stream()
                    .flatMap(file -> file.chunkIds().stream())
                    .toList());
            deleteKey(manifestKey(projectKey));
            deleteKey(versionKey(projectKey));
            logger.info("RAG limpo para o projeto {}", path);
        }
    }

    public List<Document> searchContext(String query, List<String> projectPaths) {
        if (query == null || query.isBlank() || projectPaths == null || projectPaths.isEmpty()) {
            return List.of();
        }

        List<Path> roots = projectPaths.stream()
                .filter(path -> path != null && !path.isBlank())
                .map(path -> Paths.get(path).toAbsolutePath().normalize())
                .toList();
        if (roots.isEmpty()) {
            return List.of();
        }

        String namespace = roots.stream()
                .map(this::projectKey)
                .sorted()
                .reduce((left, right) -> left + ":" + right)
                .orElse("");
        String cacheKey = cacheKey(namespace, query);
        List<Document> cached = readCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(searchCandidateLimit)
                .similarityThreshold(similarityThreshold)
                .build();
        List<Document> results = vectorStoreResolver.active().similaritySearch(searchRequest).stream()
                .filter(document -> belongsToProjects(document, roots))
                .limit(searchResultLimit)
                .toList();
        writeCache(cacheKey, results);
        return results;
    }

    private void indexSingleProject(Path root) {
        if (!Files.isDirectory(root)) {
            logger.warn("O caminho {} não é um diretório válido. Pulando indexação RAG.", root);
            return;
        }

        String projectKey = projectKey(root);
        Manifest previous = readManifest(projectKey);
        Map<String, ScannedFile> current = scan(root);
        Map<String, FileManifest> next = new LinkedHashMap<>();
        List<Document> documentsToAdd = new ArrayList<>();

        for (Map.Entry<String, ScannedFile> entry : current.entrySet()) {
            String relativePath = entry.getKey();
            ScannedFile scanned = entry.getValue();
            FileManifest oldFile = previous.files().get(relativePath);
            if (oldFile != null && oldFile.fileHash().equals(scanned.fileHash())) {
                next.put(relativePath, oldFile);
                continue;
            }

            if (oldFile != null) {
                deleteChunks(oldFile.chunkIds());
            }

            List<Document> chunks = splitFile(projectKey, root, relativePath, scanned);
            List<String> chunkIds = chunks.stream().map(Document::getId).toList();
            next.put(relativePath, new FileManifest(scanned.fileHash(), chunkIds));
            documentsToAdd.addAll(chunks);
        }

        for (Map.Entry<String, FileManifest> oldEntry : previous.files().entrySet()) {
            if (!current.containsKey(oldEntry.getKey())) {
                deleteChunks(oldEntry.getValue().chunkIds());
            }
        }

        addInBatches(documentsToAdd);
        writeManifest(projectKey, new Manifest(root.toString(), next));
        incrementVersion(projectKey);
        logger.info(
                "RAG do projeto {}: {} arquivos lidos, {} chunks atualizados, {} removidos",
                root,
                current.size(),
                documentsToAdd.size(),
                previous.files().size() - next.size());
    }

    /**
     * Sends the chunks to the vector store in slices, one slice at a time.
     *
     * <p>A single {@code add} of the whole project asks the embedding model for thousands of vectors
     * in one call. Measured against {@code nomic-embed-text} on this machine, the per-chunk cost
     * stops improving past ~8 chunks per call (100 ms alone, 51 ms at 8, 48 ms at 32), so slicing
     * costs no throughput. What it buys is a bounded request: the first indexing of a project no
     * longer competes with the chat model for RAM in one burst.
     *
     * <p>Sequential on purpose. Parallel batches on a 16 GB machine make the embedding model and the
     * chat model fight for the same memory, and the chat is what the user is waiting on.
     */
    private void addInBatches(List<Document> documents) {
        for (int start = 0; start < documents.size(); start += embeddingBatchSize) {
            int end = Math.min(start + embeddingBatchSize, documents.size());
            vectorStoreResolver.active().add(documents.subList(start, end));
        }
    }

    private Map<String, ScannedFile> scan(Path root) {
        Map<String, ScannedFile> files = new LinkedHashMap<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(root)
                                    && IGNORED_DIRECTORIES.contains(
                                            dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (files.size() >= MAX_FILES_TO_SCAN
                            || !isTextFile(file.getFileName().toString())) {
                        return files.size() >= MAX_FILES_TO_SCAN ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }
                    try {
                        String content = Files.readString(file, StandardCharsets.UTF_8);
                        if (!content.isBlank()) {
                            String relative = root.relativize(file).toString();
                            files.put(relative, new ScannedFile(content, contentHash(relative, content)));
                        }
                    } catch (Exception exception) {
                        logger.warn("Arquivo ignorado no RAG: {}", file, exception);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            logger.error("Erro ao varrer o diretório RAG {}", root, exception);
        }
        return files;
    }

    private List<Document> splitFile(String projectKey, Path root, String relativePath, ScannedFile file) {
        try {
            // Codigo corta por estrutura; o resto (markdown, json, texto) segue por tamanho, onde
            // nao ha fronteira sintatica que valha respeitar. Ver CodeAwareSplitter.
            List<String> textos;
            if (CodeAwareSplitter.handles(relativePath)) {
                textos = codeSplitter.split(file.content());
            } else {
                Document source = new Document(
                        file.content(),
                        Map.of("source", root.resolve(relativePath).toString()));
                textos = textSplitter.apply(List.of(source)).stream()
                        .map(Document::getText)
                        .toList();
            }
            List<Document> result = new ArrayList<>();
            for (int index = 0; index < textos.size(); index++) {
                String textoDoChunk = textos.get(index);
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("source", root.resolve(relativePath).toString());
                metadata.put("filename", relativePath);
                metadata.put("relativePath", relativePath);
                metadata.put("projectRoot", root.toString());
                metadata.put("projectKey", projectKey);
                metadata.put("fileHash", file.fileHash());
                metadata.put("chunkIndex", index);
                String id =
                        "avento-rag-" + sha256(projectKey + ":" + relativePath + ":" + file.fileHash() + ":" + index);
                result.add(new Document(id, textoDoChunk, metadata));
            }
            return result;
        } catch (Exception exception) {
            logger.warn("Arquivo ignorado por falha ao gerar chunks: {}", relativePath, exception);
            return List.of();
        }
    }

    private boolean belongsToProjects(Document document, List<Path> roots) {
        Object projectRoot = document.getMetadata().get("projectRoot");
        Object source = document.getMetadata().get("source");
        if (!(projectRoot instanceof String root) || !(source instanceof String sourcePath)) {
            return false;
        }
        Path normalizedRoot = Paths.get(root).toAbsolutePath().normalize();
        Path normalizedSource = Paths.get(sourcePath).toAbsolutePath().normalize();
        return roots.stream()
                .anyMatch(candidate -> candidate.equals(normalizedRoot) && normalizedSource.startsWith(candidate));
    }

    private Manifest readManifest(String projectKey) {
        try {
            String raw = redisTemplate.opsForValue().get(manifestKey(projectKey));
            return raw == null ? new Manifest("", Map.of()) : mapper.readValue(raw, Manifest.class);
        } catch (Exception exception) {
            logger.warn("Não foi possível ler o manifesto RAG {}", projectKey, exception);
            return new Manifest("", Map.of());
        }
    }

    private void writeManifest(String projectKey, Manifest manifest) {
        try {
            redisTemplate.opsForValue().set(manifestKey(projectKey), mapper.writeValueAsString(manifest));
        } catch (Exception exception) {
            logger.warn("Não foi possível salvar o manifesto RAG {}", projectKey, exception);
        }
    }

    private List<Document> readCache(String key) {
        try {
            String raw = redisTemplate.opsForValue().get(key);
            if (raw == null) {
                return null;
            }
            List<CachedChunk> chunks = mapper.readValue(raw, new TypeReference<>() {});
            return chunks.stream()
                    .map(chunk -> new Document(chunk.id(), chunk.content(), chunk.metadata()))
                    .toList();
        } catch (Exception exception) {
            return null;
        }
    }

    private void writeCache(String key, List<Document> documents) {
        try {
            List<CachedChunk> chunks = documents.stream()
                    .map(document -> new CachedChunk(document.getId(), document.getText(), document.getMetadata()))
                    .toList();
            redisTemplate.opsForValue().set(key, mapper.writeValueAsString(chunks), SEARCH_CACHE_TTL);
        } catch (Exception exception) {
            logger.debug("Cache RAG indisponível", exception);
        }
    }

    private void deleteChunks(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        try {
            // Em lotes pelo mesmo motivo do add, que eu havia batchado deixando este de fora: o
            // RedisVectorStore manda a remocao inteira num pipeline so, e o Redis e single-thread.
            // Num indice de 39 mil documentos isso o segura tempo suficiente para OUTROS clientes
            // estourarem o timeout de conexao — foi o que derrubou a leitura de preferencias do
            // usuario durante a indexacao de partida, com o erro apontando para a vitima e nao para
            // a causa.
            for (int start = 0; start < ids.size(); start += embeddingBatchSize) {
                int end = Math.min(start + embeddingBatchSize, ids.size());
                vectorStoreResolver.active().delete(ids.subList(start, end));
            }
        } catch (Exception exception) {
            logger.warn("Não foi possível remover chunks antigos do RAG", exception);
        }
    }

    private void incrementVersion(String projectKey) {
        try {
            redisTemplate.opsForValue().increment(versionKey(projectKey));
        } catch (Exception exception) {
            logger.debug("Não foi possível atualizar a versão do RAG", exception);
        }
    }

    private void deleteKey(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception exception) {
            logger.debug("Não foi possível remover a chave RAG {}", key, exception);
        }
    }

    private String cacheKey(String namespace, String query) {
        String version = redisTemplate.opsForValue().get(versionKey(namespace));
        // O índice ativo entra na chave: trocar o modelo de embedding muda o que uma mesma pergunta
        // deve devolver, e sem isso a resposta calculada pelo modelo anterior sobreviveria à troca.
        String searchProfile = vectorStoreResolver.activeIndexName() + ":" + similarityThreshold + ":"
                + searchCandidateLimit + ":" + searchResultLimit;
        return "avento:rag:cache:"
                + namespace
                + ":"
                + (version == null ? "0" : version)
                + ":"
                + sha256(searchProfile + ":" + query);
    }

    private String manifestKey(String projectKey) {
        return "avento:rag:manifest:" + projectKey;
    }

    private String versionKey(String projectKey) {
        return "avento:rag:version:" + projectKey;
    }

    private String projectKey(Path root) {
        return sha256(root.toAbsolutePath().normalize().toString());
    }

    /**
     * Hash que decide se um arquivo precisa ser reindexado.
     *
     * <p>Leva a ESTRATEGIA DE CORTE junto do conteudo, e nao so o conteudo. Sem isso, trocar o
     * chunker nao reindexava nada: o manifesto comparava hash de arquivo, os arquivos nao tinham
     * mudado, e o indice seguia servindo chunks cortados pelo metodo antigo — para sempre, ate
     * alguem editar o arquivo. Verificado no log de uma subida real: "94 arquivos lidos, 0 chunks
     * atualizados" logo depois de trocar o corte.
     */
    private String contentHash(String relativePath, String content) {
        String strategy = CodeAwareSplitter.handles(relativePath) ? CHUNK_STRATEGY : "size";
        return sha256(strategy + ":" + content);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 não está disponível", exception);
        }
    }

    private boolean isTextFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".js")
                || lower.endsWith(".ts")
                || lower.endsWith(".tsx")
                || lower.endsWith(".jsx")
                || lower.endsWith(".java")
                || lower.endsWith(".xml")
                || lower.endsWith(".html")
                || lower.endsWith(".css")
                || lower.endsWith(".json")
                || lower.endsWith(".md")
                || lower.endsWith(".txt")
                || lower.endsWith(".properties")
                || lower.endsWith(".yml")
                || lower.endsWith(".yaml")
                || lower.endsWith(".py")
                || lower.endsWith(".go");
    }
}
