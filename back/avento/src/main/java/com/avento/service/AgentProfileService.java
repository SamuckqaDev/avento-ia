package com.avento.service;

import com.avento.model.AgentProfile;
import com.avento.repository.AgentProfileRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * CRUD de agentes especializados (personas), sempre escopado por usuário — um usuário nunca lê nem
 * altera agente de outro. Garante que sempre exista exatamente um agente default (o "Generalista"),
 * usado como fallback quando o roteamento não encontra um agente específico para a tarefa.
 */
@Service
public class AgentProfileService {

    private static final int MAX_NAME_CHARS = 80;
    private static final int MAX_TEXT_CHARS = 4000;

    private final AgentProfileRepository repository;

    public AgentProfileService(AgentProfileRepository repository) {
        this.repository = repository;
    }

    public List<AgentProfile> list(UUID userId) {
        UUID owner = requireUser(userId);
        if (!repository.existsByUserId(owner)) {
            ensureDefault(owner);
        }
        return repository.findByUserIdOrderByUpdatedAtDesc(owner);
    }

    public AgentProfile get(UUID userId, Long id) {
        return require(userId, id);
    }

    public AgentProfile create(
            UUID userId,
            String name,
            String specialty,
            String systemInstructions,
            String allowedTools,
            String triggers,
            String model,
            boolean isDefault) {
        UUID owner = requireUser(userId);
        AgentProfile agent = new AgentProfile(
                owner,
                bounded(name, MAX_NAME_CHARS),
                bounded(specialty, MAX_TEXT_CHARS),
                bounded(systemInstructions, MAX_TEXT_CHARS),
                bounded(allowedTools, MAX_TEXT_CHARS),
                bounded(triggers, MAX_TEXT_CHARS),
                blankToNull(model),
                isDefault);
        AgentProfile saved = repository.save(agent);
        if (isDefault) {
            promoteToSoleDefault(owner, saved.getId());
        }
        return saved;
    }

    public AgentProfile update(
            UUID userId,
            Long id,
            String name,
            String specialty,
            String systemInstructions,
            String allowedTools,
            String triggers,
            String model,
            Boolean isDefault) {
        AgentProfile agent = require(userId, id);
        if (name != null && !name.isBlank()) {
            agent.setName(bounded(name, MAX_NAME_CHARS));
        }
        if (specialty != null) {
            agent.setSpecialty(bounded(specialty, MAX_TEXT_CHARS));
        }
        if (systemInstructions != null) {
            agent.setSystemInstructions(bounded(systemInstructions, MAX_TEXT_CHARS));
        }
        if (allowedTools != null) {
            agent.setAllowedTools(bounded(allowedTools, MAX_TEXT_CHARS));
        }
        if (triggers != null) {
            agent.setTriggers(bounded(triggers, MAX_TEXT_CHARS));
        }
        if (model != null) {
            agent.setModel(blankToNull(model));
        }
        if (isDefault != null && isDefault) {
            agent.setDefault(true);
        }
        AgentProfile saved = repository.save(agent);
        if (Boolean.TRUE.equals(isDefault)) {
            promoteToSoleDefault(agent.getUserId(), saved.getId());
        }
        return saved;
    }

    public void delete(UUID userId, Long id) {
        AgentProfile agent = require(userId, id);
        boolean wasDefault = agent.isDefault();
        repository.delete(agent);
        // Nunca deixar o usuário sem default: se apagou o default, garante outro (ou recria o Generalista).
        if (wasDefault) {
            ensureDefault(agent.getUserId());
        }
    }

    /** Agente de fallback do usuário; cria o "Generalista" se ainda não houver nenhum default. */
    public AgentProfile resolveDefault(UUID userId) {
        UUID owner = requireUser(userId);
        return repository.findFirstByUserIdAndIsDefaultTrue(owner).orElseGet(() -> ensureDefault(owner));
    }

    private AgentProfile ensureDefault(UUID userId) {
        return repository.findFirstByUserIdAndIsDefaultTrue(userId).orElseGet(() -> {
            List<AgentProfile> existing = repository.findByUserIdOrderByUpdatedAtDesc(userId);
            if (!existing.isEmpty()) {
                AgentProfile promote = existing.get(0);
                promote.setDefault(true);
                return repository.save(promote);
            }
            return repository.save(new AgentProfile(
                    userId,
                    "Generalista",
                    "Agente de uso geral para qualquer tarefa.",
                    "Você é um agente de desenvolvimento cuidadoso. Execute apenas a tarefa pedida, "
                            + "com o mínimo de alterações, e explique o que fez.",
                    "",
                    "",
                    null,
                    true));
        });
    }

    // Mantém apenas UM default por usuário: desmarca os outros.
    private void promoteToSoleDefault(UUID userId, Long defaultId) {
        for (AgentProfile agent : repository.findByUserIdOrderByUpdatedAtDesc(userId)) {
            if (!agent.getId().equals(defaultId) && agent.isDefault()) {
                agent.setDefault(false);
                repository.save(agent);
            }
        }
    }

    private AgentProfile require(UUID userId, Long id) {
        return repository
                .findByIdAndUserId(id, requireUser(userId))
                .orElseThrow(() -> new IllegalArgumentException("Agente não encontrado: " + id));
    }

    private UUID requireUser(UUID userId) {
        if (userId == null) {
            throw new SecurityException("Usuário autenticado é obrigatório para acessar agentes.");
        }
        return userId;
    }

    private String bounded(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() > max ? trimmed.substring(0, max).strip() : trimmed;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
