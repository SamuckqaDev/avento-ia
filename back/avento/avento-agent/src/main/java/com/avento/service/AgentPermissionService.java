package com.avento.service;

import com.avento.model.AgentPermissionRule;
import com.avento.repository.AgentPermissionRuleRepository;
import com.avento.service.dto.*;
import com.avento.service.tools.RunToolPolicyRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgentPermissionService {

    private static final String DECISION_ALLOW = "ALLOW";

    private final Optional<AgentPermissionRuleRepository> repository;
    private final UserSettingsService userSettingsService;
    private final RunToolPolicyRegistry runPolicyRegistry;

    @Value("${avento.agent.auto-approve-all:false}")
    private boolean autoApproveAll;

    public AgentPermissionService(Optional<AgentPermissionRuleRepository> repository) {
        this(repository, null, null);
    }

    @Autowired
    public AgentPermissionService(
            Optional<AgentPermissionRuleRepository> repository,
            ObjectProvider<UserSettingsService> userSettingsServiceProvider,
            ObjectProvider<RunToolPolicyRegistry> runPolicyRegistryProvider) {
        this.repository = repository;
        this.userSettingsService =
                userSettingsServiceProvider == null ? null : userSettingsServiceProvider.getIfAvailable();
        this.runPolicyRegistry = runPolicyRegistryProvider == null ? null : runPolicyRegistryProvider.getIfAvailable();
    }

    public boolean canAutoApprove(String toolName, JsonNode arguments, List<String> workspaceRoots) {
        return canAutoApprove(null, null, toolName, arguments, workspaceRoots);
    }

    public boolean canAutoApprove(UUID userId, String toolName, JsonNode arguments, List<String> workspaceRoots) {
        return canAutoApprove(null, userId, toolName, arguments, workspaceRoots);
    }

    /**
     * Ponto único de decisão de aprovação. A ordem importa: execução autônoma decide primeiro, por
     * ORIGEM da run — uma tarefa agendada roda sem ninguém para responder, e pedir aprovação ali é
     * o mesmo que travar até o timeout. Só depois entra a preferência do usuário, que vale para a
     * conversa interativa.
     */
    public boolean canAutoApprove(
            String runId, UUID userId, String toolName, JsonNode arguments, List<String> workspaceRoots) {
        if (autoApproveAll) {
            return true;
        }
        if (runPolicyRegistry != null && runPolicyRegistry.isAutonomous(runId)) {
            return true;
        }
        // Fallback FALSE de propósito: se a preferência não pôde ser lida (Redis vazio depois de um
        // restart, Redis fora do ar), o certo é PERGUNTAR. Com fallback true, todo reinício do dev
        // desligava silenciosamente a aprovação e o botão do header não significava nada.
        if (userId != null && userSettingsService != null && userSettingsService.autoApproveAllEnabled(userId, false)) {
            return true;
        }
        return repository
                .map(repo -> repo.findByUserIdAndToolNameAndResourceKeyAndProjectPathOrderByCreatedAtDesc(
                        userId, toolName, resourceKey(toolName, arguments), projectPath(workspaceRoots)))
                .orElseGet(List::of)
                .stream()
                .anyMatch(this::isActiveAllowRule);
    }

    public void rememberAllow(
            String toolName, JsonNode arguments, List<String> workspaceRoots, ApprovalMemory approvalMemory) {
        rememberAllow(null, toolName, arguments, workspaceRoots, approvalMemory);
    }

    public void rememberAllow(
            UUID userId,
            String toolName,
            JsonNode arguments,
            List<String> workspaceRoots,
            ApprovalMemory approvalMemory) {
        if (approvalMemory == null || approvalMemory.duration() == null && !approvalMemory.always()) {
            return;
        }

        repository.ifPresent(repo -> {
            LocalDateTime expiresAt =
                    approvalMemory.always() ? null : LocalDateTime.now().plus(approvalMemory.duration());
            repo.save(new AgentPermissionRule(
                    userId,
                    projectPath(workspaceRoots),
                    toolName,
                    resourceKey(toolName, arguments),
                    DECISION_ALLOW,
                    expiresAt));
        });
    }

    private boolean isActiveAllowRule(AgentPermissionRule rule) {
        if (!DECISION_ALLOW.equals(rule.getDecision())) {
            return false;
        }
        return rule.getExpiresAt() == null || rule.getExpiresAt().isAfter(LocalDateTime.now());
    }

    private String projectPath(List<String> workspaceRoots) {
        if (workspaceRoots == null || workspaceRoots.isEmpty() || workspaceRoots.get(0) == null) {
            return "local";
        }
        return workspaceRoots.get(0);
    }

    private String resourceKey(String toolName, JsonNode arguments) {
        if (arguments == null || arguments.isMissingNode() || arguments.isNull()) {
            return toolName;
        }

        String value = firstText(arguments, "command", "path", "appName", "browserName", "url", "shortcutName");
        if (value.isBlank()) {
            value = arguments.toString();
        }
        return toolName + ":" + value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
