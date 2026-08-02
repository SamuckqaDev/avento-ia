package com.avento.service.rag;

import com.avento.service.RagService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * The single entry point behind the {@code search_code} tool.
 *
 * <p>Two search paths existed side by side and never met: {@link RagService} held the real vector
 * search but only a REST controller called it, while the agent's tool used {@link CodebaseRagService},
 * which scores with {@code content.contains(token)}. This service puts the vector path in front and
 * keeps the literal one as the fallback, so the tool answers from the first message of a project —
 * before the index has been built — and gets better once it is warm.
 */
@Service
public class CodeSearchService {

    private static final Logger logger = LoggerFactory.getLogger(CodeSearchService.class);

    /** Which path produced the hits, so the caller can tell the model what it is looking at. */
    public enum Strategy {
        VECTOR,
        LITERAL
    }

    public record Hit(String filePath, int startLine, int endLine, String snippet, double score) {}

    public record Result(Strategy strategy, List<Hit> hits) {}

    private static final int SNIPPET_MAX_CHARS = 600;

    private final RagService ragService;
    private final CodebaseRagService codebaseRagService;
    private final WorkspaceIndexingService indexingService;

    public CodeSearchService(
            RagService ragService, CodebaseRagService codebaseRagService, WorkspaceIndexingService indexingService) {
        this.ragService = ragService;
        this.codebaseRagService = codebaseRagService;
        this.indexingService = indexingService;
    }

    /**
     * @param path directory to search under — the project root, or a folder inside it
     */
    public Result search(Path path, String query, int maxResults) {
        if (path == null || query == null || query.isBlank()) {
            return new Result(Strategy.LITERAL, List.of());
        }

        List<Hit> vectorHits = vectorSearch(path, query, maxResults);
        if (!vectorHits.isEmpty()) {
            return new Result(Strategy.VECTOR, vectorHits);
        }

        // Empty is not the same as "nothing matches": the index may still be building, and the 0.62
        // similarity threshold was calibrated on prose, not on code. Literal matching answers either way.
        return new Result(Strategy.LITERAL, literalSearch(path, query, maxResults));
    }

    private List<Hit> vectorSearch(Path path, String query, int maxResults) {
        // The index is keyed by project root, but the model often searches a subfolder. Find the
        // project the folder belongs to, query that, and narrow the hits back down to the folder.
        Optional<Path> projectRoot = indexingService.indexedRootFor(path);
        if (projectRoot.isEmpty() || !indexingService.isReady(projectRoot.get())) {
            return List.of();
        }
        try {
            return ragService.searchContext(query, List.of(projectRoot.get().toString())).stream()
                    .map(this::toHit)
                    .filter(hit -> isUnder(hit.filePath(), path))
                    .limit(maxResults)
                    .toList();
        } catch (Exception exception) {
            logger.warn("Vector search failed for {}; falling back to literal matching", path, exception);
            return List.of();
        }
    }

    private boolean isUnder(String filePath, Path directory) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        return Paths.get(filePath).toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize());
    }

    private List<Hit> literalSearch(Path root, String query, int maxResults) {
        List<Hit> hits = new ArrayList<>();
        for (CodebaseRagService.SearchResult match : codebaseRagService.search(root.toString(), query, maxResults)) {
            hits.add(new Hit(match.filePath(), match.startLine(), match.endLine(), match.snippet(), match.score()));
        }
        return hits;
    }

    private Hit toHit(Document document) {
        String filePath = String.valueOf(document.getMetadata().getOrDefault("source", ""));
        String text = document.getText() == null ? "" : document.getText();
        double score = document.getScore() == null ? 0.0 : document.getScore();
        int startLine = firstLineOf(filePath, text);
        int endLine = startLine == 0 ? 0 : startLine + countLines(text) - 1;
        return new Hit(filePath, startLine, endLine, truncate(text), score);
    }

    /**
     * Where a chunk starts in its file.
     *
     * <p>The splitter works in tokens and keeps no line numbers, but a hit the user cannot open is
     * half an answer. Locating the chunk's text back in the file is cheap and exact when it is found;
     * when it is not, 0 says "unknown" rather than inventing a line.
     */
    private int firstLineOf(String filePath, String chunkText) {
        if (filePath.isBlank() || chunkText.isBlank()) {
            return 0;
        }
        try {
            String content = Files.readString(Paths.get(filePath), StandardCharsets.UTF_8);
            int offset = content.indexOf(chunkText.strip());
            if (offset < 0) {
                return 0;
            }
            return (int) content.substring(0, offset).lines().count() + 1;
        } catch (Exception exception) {
            return 0;
        }
    }

    private int countLines(String text) {
        return Math.max(1, (int) text.lines().count());
    }

    private String truncate(String text) {
        return text.length() > SNIPPET_MAX_CHARS ? text.substring(0, SNIPPET_MAX_CHARS) + "..." : text;
    }
}
