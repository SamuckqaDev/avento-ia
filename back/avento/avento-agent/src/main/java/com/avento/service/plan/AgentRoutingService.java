package com.avento.service.plan;

import com.avento.model.AgentProfile;
import com.avento.model.AgentTask;
import com.avento.repository.AgentProfileRepository;
import com.avento.service.AgentProfileService;
import com.avento.service.intent.IntentEmbeddingClassifier;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Escolhe qual agente (persona) executa uma tarefa. Ordem: (1) escolha manual do usuário
 * ({@code assignedAgentId}); (2) casamento automático por gatilhos/especialidade; (3) fallback no
 * agente default. Nunca lança — sempre retorna um agente utilizável (garante o default).
 */
@Service
public class AgentRoutingService {

    private final AgentProfileRepository repository;
    private final AgentProfileService agentProfileService;

    @Autowired(required = false)
    private IntentEmbeddingClassifier embeddingClassifier;

    public AgentRoutingService(AgentProfileRepository repository, AgentProfileService agentProfileService) {
        this.repository = repository;
        this.agentProfileService = agentProfileService;
    }

    /** Resultado do roteamento: o agente escolhido e por quê (para exibir e gravar em agentRationale). */
    public record Routed(AgentProfile agent, String rationale) {}

    public Routed pick(UUID userId, AgentTask task) {
        // 1. Escolha manual do usuário vence tudo.
        if (task.getAssignedAgentId() != null) {
            AgentProfile chosen = repository
                    .findByIdAndUserId(task.getAssignedAgentId(), userId)
                    .orElse(null);
            if (chosen != null) {
                return new Routed(chosen, "Escolhido manualmente pelo usuário.");
            }
        }

        List<AgentProfile> agents = repository.findByUserIdOrderByUpdatedAtDesc(userId);

        // 2. Classificação semântica vetorial por IntentEmbeddingClassifier (Ollama / nomic-embed-text)
        if (embeddingClassifier != null) {
            String text = safe(task.getTitle()) + " " + safe(task.getDetails());
            var intentsOpt = embeddingClassifier.classify(text);
            if (intentsOpt.isPresent() && !intentsOpt.get().isEmpty()) {
                String detectedIntents = intentsOpt.get().toString();
                for (AgentProfile agent : agents) {
                    if (agent.getSpecialty() != null && !agent.getSpecialty().isBlank()) {
                        String normSpec = agent.getSpecialty().toLowerCase(Locale.ROOT);
                        for (var intent : intentsOpt.get()) {
                            if (normSpec.contains(intent.name().toLowerCase(Locale.ROOT))) {
                                return new Routed(
                                        agent, "Classificado via embedding de intenção (" + detectedIntents + ").");
                            }
                        }
                    }
                }
            }
        }

        String haystack = normalize((safe(task.getTitle()) + " " + safe(task.getDetails())));

        // 3. Fallback de busca textual por gatilho/especialidade.
        AgentProfile best = null;
        int bestScore = 0;
        for (AgentProfile agent : agents) {
            int score = matchScore(agent, haystack);
            if (score > bestScore) {
                bestScore = score;
                best = agent;
            }
        }
        if (best != null) {
            return new Routed(best, "Casou com a especialidade/gatilhos do agente \"" + best.getName() + "\".");
        }

        // 4. Fallback: agente default (criado sob demanda se não existir).
        AgentProfile fallback = agentProfileService.resolveDefault(userId);
        return new Routed(fallback, "Nenhum agente específico casou; usando o agente padrão.");
    }

    private int matchScore(AgentProfile agent, String haystack) {
        int score = 0;
        for (String keyword : tokens(agent.getTriggers())) {
            if (haystack.contains(keyword)) {
                score += 2; // gatilho explícito pesa mais
            }
        }
        for (String keyword : tokens(agent.getSpecialty())) {
            if (keyword.length() >= 4 && haystack.contains(keyword)) {
                score += 1;
            }
        }
        return score;
    }

    private List<String> tokens(String csvOrText) {
        if (csvOrText == null || csvOrText.isBlank()) {
            return List.of();
        }
        return List.of(normalize(csvOrText).split("[,\\s]+")).stream()
                .filter(token -> !token.isBlank())
                .distinct()
                .toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
