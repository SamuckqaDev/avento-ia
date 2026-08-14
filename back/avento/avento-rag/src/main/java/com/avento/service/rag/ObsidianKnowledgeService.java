package com.avento.service.rag;

import com.avento.dto.ObsidianVaultStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Makes an Obsidian vault an explicit local source for the existing Redis-backed RAG.
 *
 * <p>The vault is ordinary Markdown: Avento neither requires the Obsidian application nor writes to
 * it during search. Initialization is an explicit action and only creates missing starter notes. A
 * note retrieved from the vault is evidence for an answer, never a system instruction, policy
 * override, or authorization to execute a tool.
 */
@Service
public class ObsidianKnowledgeService {

    private static final String VAULT_README = """
            # Avento Knowledge Vault

            This is a regular Obsidian vault that Avento can index as local reference material.

            - Put stable notes and study material in `10-Knowledge`.
            - Put project-specific notes in `20-Projects`.
            - Use `30-Memory` for notes you want to review manually.
            - `40-Policies` is reference only: its notes do not replace Avento's system policies or approve actions.

            In Avento, open Settings → Knowledge, then request a reindex after editing notes.
            """;
    private static final String POLICY_README = """
            # Reference policies

            Notes in this folder are consulted only when relevant to a question. They are not executable
            instructions, do not override Avento's built-in safety rules, and do not grant permission for tools.
            """;

    private final RagService ragService;
    private final WorkspaceIndexingService indexingService;
    private final Path vaultPath;

    public ObsidianKnowledgeService(
            RagService ragService,
            WorkspaceIndexingService indexingService,
            @Value("${avento.obsidian.vault-path:${user.home}/.avento/obsidian-vault}") String vaultPath) {
        this.ragService = ragService;
        this.indexingService = indexingService;
        this.vaultPath = Paths.get(vaultPath).toAbsolutePath().normalize();
    }

    public ObsidianVaultStatus status() {
        if (!Files.isDirectory(vaultPath)) {
            return new ObsidianVaultStatus(
                    false,
                    vaultPath.toString(),
                    WorkspaceIndexingService.IndexState.UNKNOWN.name(),
                    "O vault ainda não foi criado. Inicialize-o para usar o Obsidian como conhecimento local.");
        }
        WorkspaceIndexingService.IndexState state = indexingService.stateOf(vaultPath);
        return new ObsidianVaultStatus(
                true,
                vaultPath.toString(),
                state.name(),
                messageFor(state));
    }

    /** Creates only the missing Avento-managed starter structure and queues its first index. */
    public ObsidianVaultStatus initialize() {
        rejectUnsafeRoot();
        try {
            Files.createDirectories(vaultPath);
            Files.createDirectories(vaultPath.resolve("00-Inbox"));
            Files.createDirectories(vaultPath.resolve("10-Knowledge"));
            Files.createDirectories(vaultPath.resolve("20-Projects"));
            Files.createDirectories(vaultPath.resolve("30-Memory"));
            Path policies = Files.createDirectories(vaultPath.resolve("40-Policies"));
            writeIfAbsent(vaultPath.resolve("README.md"), VAULT_README);
            writeIfAbsent(policies.resolve("README.md"), POLICY_README);
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível criar o vault do Obsidian em " + vaultPath, exception);
        }
        indexingService.requestReindexing(vaultPath);
        return status();
    }

    /** Queues an incremental reindex. Hashes in the RAG manifest ensure unchanged notes are not embedded again. */
    public ObsidianVaultStatus reindex() {
        if (!Files.isDirectory(vaultPath)) {
            throw new IllegalStateException("Inicialize o vault do Obsidian antes de indexá-lo.");
        }
        boolean queued = indexingService.requestReindexing(vaultPath);
        if (!queued && indexingService.stateOf(vaultPath) != WorkspaceIndexingService.IndexState.INDEXING) {
            throw new IllegalStateException("Não foi possível agendar a indexação do vault do Obsidian.");
        }
        return status();
    }

    /** Returns only chunks rooted in this vault; no other project can leak into the result. */
    public List<Document> search(String query) {
        if (query == null || query.isBlank() || !Files.isDirectory(vaultPath)) {
            return List.of();
        }
        return ragService.searchContext(query, List.of(vaultPath.toString()));
    }

    private void rejectUnsafeRoot() {
        Path home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (vaultPath.equals(home) || vaultPath.getParent() == null) {
            throw new IllegalStateException("O vault do Obsidian precisa ser uma pasta específica, não a pasta pessoal.");
        }
    }

    private void writeIfAbsent(Path file, String content) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }
    }

    private String messageFor(WorkspaceIndexingService.IndexState state) {
        return switch (state) {
            case READY -> "Vault pronto: as notas podem enriquecer as próximas conversas.";
            case INDEXING -> "Vault sendo indexado em segundo plano; a busca pode estar parcial por alguns instantes.";
            case FAILED -> "A última indexação falhou. Verifique o modelo de embeddings e tente novamente.";
            case UNKNOWN -> "Vault encontrado. Clique em reindexar para disponibilizar as notas ao chat.";
        };
    }
}
