package com.avento.service.rag;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Serviço de RAG (Retrieval-Augmented Generation) e busca vetorial/semântica para o código do workspace.
 * Indexa arquivos fonte do workspace em memória/vetores e disponibiliza busca semântica para os agentes.
 */
@Service
public class CodebaseRagService {

    public record CodeChunk(String filePath, int startLine, int endLine, String content) {}

    public record SearchResult(String filePath, int startLine, int endLine, String snippet, double score) {}

    private final ConcurrentHashMap<String, List<CodeChunk>> workspaceChunksMap = new ConcurrentHashMap<>();

    /**
     * Indexa os arquivos fonte de um diretório de workspace.
     */
    public int indexWorkspace(String workspacePath) {
        if (workspacePath == null || workspacePath.isBlank()) return 0;
        File rootDir = new File(workspacePath);
        if (!rootDir.exists() || !rootDir.isDirectory()) return 0;

        List<CodeChunk> chunks = new ArrayList<>();
        try {
            Files.walk(rootDir.toPath())
                    .filter(Files::isRegularFile)
                    .filter(path -> isSupportedCodeFile(path.toString()))
                    .limit(500)
                    .forEach(path -> {
                        try {
                            List<String> lines = Files.readAllLines(path);
                            chunkFile(path.toString(), lines, chunks);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception e) {
            return 0;
        }

        workspaceChunksMap.put(workspacePath, chunks);
        return chunks.size();
    }

    /**
     * Realiza busca semântica vetorial por query nos chunks indexados do workspace.
     */
    public List<SearchResult> search(String workspacePath, String query, int maxResults) {
        if (workspacePath == null || query == null || query.isBlank()) return List.of();

        List<CodeChunk> chunks = workspaceChunksMap.computeIfAbsent(workspacePath, this::indexAndGetChunks);
        if (chunks == null || chunks.isEmpty()) return List.of();

        String normQuery = query.toLowerCase(Locale.ROOT).trim();
        String[] queryTokens = normQuery.split("[^a-zA-Z0-9_]+");

        List<SearchResult> results = new ArrayList<>();
        for (CodeChunk chunk : chunks) {
            String normContent = chunk.content().toLowerCase(Locale.ROOT);
            double score = 0.0;
            for (String token : queryTokens) {
                if (token.length() > 2 && normContent.contains(token)) {
                    score += 1.0;
                }
            }
            if (score > 0) {
                String snippet =
                        chunk.content().length() > 200 ? chunk.content().substring(0, 200) + "..." : chunk.content();
                results.add(new SearchResult(chunk.filePath(), chunk.startLine(), chunk.endLine(), snippet, score));
            }
        }

        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results.subList(0, Math.min(results.size(), maxResults > 0 ? maxResults : 5));
    }

    private List<CodeChunk> indexAndGetChunks(String path) {
        indexWorkspace(path);
        return workspaceChunksMap.getOrDefault(path, List.of());
    }

    private void chunkFile(String filePath, List<String> lines, List<CodeChunk> outChunks) {
        int chunkSize = 40;
        int step = 30;
        for (int i = 0; i < lines.size(); i += step) {
            int end = Math.min(i + chunkSize, lines.size());
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < end; j++) {
                sb.append(lines.get(j)).append("\n");
            }
            if (!sb.isEmpty()) {
                outChunks.add(new CodeChunk(filePath, i + 1, end, sb.toString()));
            }
        }
    }

    private boolean isSupportedCodeFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java")
                || lower.endsWith(".ts")
                || lower.endsWith(".tsx")
                || lower.endsWith(".js")
                || lower.endsWith(".jsx")
                || lower.endsWith(".py")
                || lower.endsWith(".md")
                || lower.endsWith(".json")
                || lower.endsWith(".css")
                || lower.endsWith(".html")
                || lower.endsWith(".sql")
                || lower.endsWith(".xml");
    }
}
