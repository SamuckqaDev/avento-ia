package com.avento.repository;

import com.avento.model.PendingToolApproval;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingToolApprovalRepository extends JpaRepository<PendingToolApproval, Long> {

    Optional<PendingToolApproval> findByApprovalId(String approvalId);

    Optional<PendingToolApproval> findByApprovalIdAndUserIdAndStatus(String approvalId, UUID userId, String status);

    Optional<PendingToolApproval> findFirstByUserIdAndStatusOrderByIdDesc(UUID userId, String status);

    List<PendingToolApproval> findByRunIdAndStatus(String runId, String status);

    long deleteByChatIdAndUserId(Long chatId, UUID userId);
}
