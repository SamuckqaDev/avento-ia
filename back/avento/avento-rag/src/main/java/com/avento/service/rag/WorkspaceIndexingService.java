package com.avento.service.rag;

import com.avento.service.RagService;
import com.avento.service.event.WorkspaceRootRegisteredEvent;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Keeps the vector index of each open project warm.
 *
 * <p>Before this existed, {@link RagService} was only reachable through its REST controller, so the
 * index of a project the agent had open was always empty and {@code search_code} could only fall
 * back to literal matching. Indexing now starts when a workspace root is registered, and runs off
 * the request thread — the first pass over a real project costs minutes and nobody should wait on it
 * to get an answer.
 */
@Service
public class WorkspaceIndexingService {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceIndexingService.class);

    public enum IndexState {
        /** Never requested for this root. */
        UNKNOWN,
        /** A pass is running; the index may be partial. */
        INDEXING,
        /** At least one pass finished; the index answers for this root. */
        READY,
        /** The last pass failed — typically the embedding model is unreachable. */
        FAILED
    }

    private final RagService ragService;
    private final boolean autoIndexEnabled;
    private final long reindexDebounceMillis;
    private final String defaultWorkspaceRoot;

    private final Map<Path, IndexState> states = new ConcurrentHashMap<>();
    private final Map<Path, ScheduledFuture<?>> pendingReindexes = new ConcurrentHashMap<>();

    // One thread, on purpose: embedding a project competes with the chat model for RAM, and on a
    // 16 GB machine the chat is what the user is waiting on. Two roots index one after the other.
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "avento-rag-indexer");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    public WorkspaceIndexingService(
            RagService ragService,
            @Value("${avento.rag.auto-index:true}") boolean autoIndexEnabled,
            @Value("${avento.rag.reindex-debounce-millis:15000}") long reindexDebounceMillis,
            @Value("${avento.workspace.default-root:}") String defaultWorkspaceRoot) {
        this.ragService = ragService;
        this.autoIndexEnabled = autoIndexEnabled;
        this.reindexDebounceMillis = Math.max(0, reindexDebounceMillis);
        this.defaultWorkspaceRoot = defaultWorkspaceRoot;
    }

    @EventListener
    public void onWorkspaceRegistered(WorkspaceRootRegisteredEvent event) {
        if (event.root() != null) {
            requestIndexing(event.root());
        }
    }

    /** Schedules a first pass for {@code root} unless one already ran or is running. */
    public void requestIndexing(Path root) {
        if (!autoIndexEnabled || root == null) {
            return;
        }
        Path normalized = normalize(root);
        if (tooBroadToIndex(normalized)) {
            return;
        }
        // FAILED is retried: the usual cause is a stopped embedding model, which comes back.
        IndexState previous = states.get(normalized);
        if (previous == IndexState.INDEXING || previous == IndexState.READY) {
            return;
        }
        states.put(normalized, IndexState.INDEXING);
        worker.execute(() -> index(normalized));
    }

    /**
     * Records that a file changed and schedules an incremental pass over its project.
     *
     * <p>The trigger is the write, not the message: indexing is incremental by file hash, so a pass
     * after an edit re-embeds only the file that changed, while a pass per message would rescan the
     * whole tree to discover nothing moved. The debounce collapses a burst of edits — an agent
     * rewriting six files in a row — into a single pass.
     */
    public void noteFileChanged(Path file) {
        if (!autoIndexEnabled || file == null) {
            return;
        }
        Optional<Path> owner = indexedRootFor(file);
        if (owner.isEmpty()) {
            return;
        }
        Path root = owner.get();
        ScheduledFuture<?> previous = pendingReindexes.put(
                root, worker.schedule(() -> reindex(root), reindexDebounceMillis, TimeUnit.MILLISECONDS));
        if (previous != null) {
            previous.cancel(false);
        }
    }

    /** Whether the vector index can be trusted to answer for {@code root}. */
    public boolean isReady(Path root) {
        return root != null && states.get(normalize(root)) == IndexState.READY;
    }

    public IndexState stateOf(Path root) {
        return root == null ? IndexState.UNKNOWN : states.getOrDefault(normalize(root), IndexState.UNKNOWN);
    }

    private void index(Path root) {
        long startedAt = System.currentTimeMillis();
        try {
            ragService.indexProject(List.of(root.toString()));
            states.put(root, IndexState.READY);
            logger.info("Vector index ready for {} in {} ms", root, System.currentTimeMillis() - startedAt);
        } catch (Exception exception) {
            states.put(root, IndexState.FAILED);
            // Not fatal: search_code falls back to literal matching while this is broken.
            logger.warn("Vector indexing failed for {}; literal search stays in use", root, exception);
        }
    }

    private void reindex(Path root) {
        pendingReindexes.remove(root);
        // A root whose first pass never succeeded goes through requestIndexing, which sets the state.
        if (states.get(root) == null || states.get(root) == IndexState.FAILED) {
            requestIndexing(root);
            return;
        }
        index(root);
    }

    /**
     * The deepest known project root containing {@code path}, so nested projects pick the closest one.
     *
     * <p>Only roots this service was told about are candidates — that is, roots someone explicitly
     * registered. Deliberately not the authorized-root set: that one also carries
     * {@code avento.workspace.default-root}, the folder that holds every project on the machine, and
     * treating it as a project would index all of them to answer one search.
     */
    public Optional<Path> indexedRootFor(Path path) {
        if (path == null) {
            return Optional.empty();
        }
        Path normalized = normalize(path);
        return states.keySet().stream()
                .filter(normalized::startsWith)
                .max(Comparator.comparingInt(Path::getNameCount));
    }

    /**
     * Raiz ampla demais para virar indice semantico.
     *
     * <p>Autorizar acesso e querer busca vetorial sao intencoes DIFERENTES, e este servico as
     * acoplou sem querer: {@code /api/filesystem/authorize-home} existe para liberar as ferramentas
     * de arquivo na home inteira com um clique, e desde que a indexacao passou a reagir ao registro
     * de raiz, esse clique virou "indexe a minha pasta pessoal".
     *
     * <p>Medido numa subida real: 9.468 chunks no indice, e a amostra apontou 226 em 250 vindos de
     * {@code /Users/<usuario>} — cache do Playwright, site-packages do Python, o que houvesse. Para
     * um projeto de 97 arquivos. O trecho certo de uma busca passa a competir com milhares que nunca
     * deveriam estar la, e cada um deles custou um embedding.
     *
     * <p>Recusa a home e a pasta que CONTEM os projetos. Nenhuma das duas e um projeto; uma pasta de
     * projeto de verdade continua entrando normalmente.
     */
    private boolean tooBroadToIndex(Path root) {
        Path home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (root.equals(home) || home.startsWith(root)) {
            logger.info("Indexacao ignorada para {}: raiz ampla demais para busca semantica", root);
            return true;
        }
        if (defaultWorkspaceRoot != null && !defaultWorkspaceRoot.isBlank()) {
            Path parentOfProjects = Paths.get(defaultWorkspaceRoot).toAbsolutePath().normalize();
            if (root.equals(parentOfProjects)) {
                logger.info("Indexacao ignorada para {}: e a pasta que contem os projetos", root);
                return true;
            }
        }
        return false;
    }

    private Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }
}
