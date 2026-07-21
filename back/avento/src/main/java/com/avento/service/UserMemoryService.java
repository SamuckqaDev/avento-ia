package com.avento.service;

import com.avento.model.UserMemory;
import com.avento.repository.UserMemoryRepository;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Memória de longo prazo do Avento (fatos/preferências que atravessam conversas). Modelo híbrido:
 * o usuário adiciona/confirma manualmente e o modelo apenas *sugere* (via ferramenta remember),
 * ficando PENDING até o usuário aprovar. Só memórias ACTIVE entram no prompt.
 */
@Service
@RequiredArgsConstructor
public class UserMemoryService {

    // Tetos do bloco injetado no prompt: memória não pode virar o novo vilão do num_ctx local.
    private static final int MAX_INJECTED = 25;
    private static final int MAX_BLOCK_CHARS = 1600;
    private static final int MAX_CONTENT_CHARS = 500;
    private static final int MAX_PENDING = 50;

    private final UserMemoryRepository repository;

    public List<UserMemory> listAll(UUID userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(requireUser(userId));
    }

    public List<UserMemory> listActive(UUID userId) {
        return repository.findByUserIdAndStatusOrderByUpdatedAtDesc(requireUser(userId), UserMemory.STATUS_ACTIVE);
    }

    public List<UserMemory> listPending(UUID userId) {
        return repository.findByUserIdAndStatusOrderByUpdatedAtDesc(requireUser(userId), UserMemory.STATUS_PENDING);
    }

    /** Adição manual do usuário: entra já ativa (o usuário é a fonte confiável). */
    public UserMemory addManual(UUID userId, String content, String category) {
        UUID owner = requireUser(userId);
        String normalized = normalize(content);
        return repository.save(
                new UserMemory(owner, normalized, category, null, UserMemory.STATUS_ACTIVE, UserMemory.ORIGIN_MANUAL));
    }

    /**
     * Sugestão vinda do modelo (ferramenta remember): entra PENDING e só influencia o prompt depois
     * que o usuário confirma. Ignora duplicatas já ativas ou já pendentes.
     */
    public SuggestionOutcome suggest(UUID userId, String content, String category) {
        UUID owner = requireUser(userId);
        String normalized = normalize(content);
        if (repository.existsByUserIdAndStatusAndContentIgnoreCase(owner, UserMemory.STATUS_ACTIVE, normalized)
                || repository.existsByUserIdAndStatusAndContentIgnoreCase(
                        owner, UserMemory.STATUS_PENDING, normalized)) {
            return new SuggestionOutcome(false, normalized);
        }
        if (repository.countByUserIdAndStatus(owner, UserMemory.STATUS_PENDING) >= MAX_PENDING) {
            throw new IllegalStateException("Limite de memórias pendentes atingido; confirme ou remova uma sugestão.");
        }
        repository.save(new UserMemory(
                owner, normalized, category, null, UserMemory.STATUS_PENDING, UserMemory.ORIGIN_SUGGESTED));
        return new SuggestionOutcome(true, normalized);
    }

    public UserMemory confirm(UUID userId, Long id) {
        UserMemory memory = require(userId, id);
        memory.setStatus(UserMemory.STATUS_ACTIVE);
        return repository.save(memory);
    }

    public UserMemory update(UUID userId, Long id, String content, String category) {
        UserMemory memory = require(userId, id);
        if (content != null && !content.isBlank()) {
            memory.setContent(normalize(content));
        }
        if (category != null && !category.isBlank()) {
            memory.setCategory(category);
        }
        return repository.save(memory);
    }

    public void delete(UUID userId, Long id) {
        repository.delete(require(userId, id));
    }

    /**
     * Bloco de memória injetado no prompt de sistema, montado só com memórias ACTIVE e limitado por
     * quantidade e por caracteres. Retorna vazio quando não há nada a lembrar.
     */
    public String promptBlock(UUID userId) {
        List<UserMemory> active = listActive(userId);
        if (active.isEmpty()) {
            return "";
        }
        StringBuilder block =
                new StringBuilder("\n\n[Memória do usuário — fatos que você já sabe de conversas anteriores]\n");
        int used = 0;
        for (UserMemory memory : active) {
            if (used >= MAX_INJECTED) {
                break;
            }
            String line = "- " + memory.getContent().strip() + "\n";
            if (block.length() + line.length() > MAX_BLOCK_CHARS && used > 0) {
                break;
            }
            block.append(line);
            used++;
        }
        return block.toString().stripTrailing();
    }

    private UserMemory require(UUID userId, Long id) {
        return repository
                .findByIdAndUserId(id, requireUser(userId))
                .orElseThrow(() -> new IllegalArgumentException("Memória não encontrada: " + id));
    }

    private UUID requireUser(UUID userId) {
        if (userId == null) {
            throw new SecurityException("Usuário autenticado é obrigatório para acessar memórias.");
        }
        return userId;
    }

    private String normalize(String content) {
        if (content == null) {
            throw new IllegalArgumentException("Conteúdo da memória não pode ser vazio.");
        }
        String trimmed = content.strip().replaceAll("\\s+", " ");
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Conteúdo da memória não pode ser vazio.");
        }
        return trimmed.length() > MAX_CONTENT_CHARS
                ? trimmed.substring(0, MAX_CONTENT_CHARS).strip()
                : trimmed;
    }

    /** Resultado de uma sugestão do modelo: {@code saved=false} quando era duplicata e foi ignorada. */
    public record SuggestionOutcome(boolean saved, String content) {}

    // Categoria padrão quando o modelo não informa uma.
    public static String defaultCategory() {
        return "fato".toLowerCase(Locale.ROOT);
    }
}
