package com.avento.repository;

import com.avento.model.AgentPermissionRule;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentPermissionRuleRepository extends JpaRepository<AgentPermissionRule, Long> {
    List<AgentPermissionRule> findByUserIdAndToolNameAndResourceKeyAndProjectPathOrderByCreatedAtDesc(
            UUID userId, String toolName, String resourceKey, String projectPath);
}
