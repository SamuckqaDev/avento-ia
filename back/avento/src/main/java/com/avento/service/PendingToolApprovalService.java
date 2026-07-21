package com.avento.service;

import com.avento.model.PendingToolApproval;
import com.avento.repository.PendingToolApprovalRepository;
import com.avento.service.dto.PendingToolExecution;
import com.avento.service.dto.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PendingToolApprovalService {

    private final PendingToolApprovalRepository repository;
    private final ObjectMapper mapper;

    @Transactional
    public void save(String approvalId, PendingToolExecution execution) {
        ToolCall toolCall = execution.toolCall();
        UUID userId = userId(toolCall);
        Long chatId = toolCall.arguments().path("_chatId").canConvertToLong()
                ? toolCall.arguments().path("_chatId").asLong()
                : null;
        if (userId == null
                || chatId == null
                || execution.runId() == null
                || execution.runId().isBlank()) {
            return;
        }
        try {
            PendingToolApproval approval = new PendingToolApproval();
            approval.setApprovalId(approvalId);
            approval.setUserId(userId);
            approval.setChatId(chatId);
            approval.setRunId(execution.runId());
            approval.setPayload(mapper.writeValueAsString(execution));
            repository.save(approval);
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível persistir a aprovação pendente.", exception);
        }
    }

    public boolean isOwnedPending(String approvalId, UUID userId) {
        return userId != null
                && repository
                        .findByApprovalIdAndUserIdAndStatus(approvalId, userId, PendingToolApproval.STATUS_PENDING)
                        .isPresent();
    }

    public boolean isResolved(String approvalId) {
        return repository
                .findByApprovalId(approvalId)
                .map(approval -> !PendingToolApproval.STATUS_PENDING.equals(approval.getStatus()))
                .orElse(false);
    }

    public Optional<String> latestPendingId(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return repository
                .findFirstByUserIdAndStatusOrderByIdDesc(userId, PendingToolApproval.STATUS_PENDING)
                .map(PendingToolApproval::getApprovalId);
    }

    public Optional<String> runIdFor(String approvalId, UUID userId) {
        return repository
                .findByApprovalIdAndUserIdAndStatus(approvalId, userId, PendingToolApproval.STATUS_PENDING)
                .map(PendingToolApproval::getRunId);
    }

    @Transactional
    public synchronized Optional<PendingToolExecution> resolve(String approvalId) {
        PendingToolApproval approval = repository.findByApprovalId(approvalId).orElse(null);
        if (approval == null || !PendingToolApproval.STATUS_PENDING.equals(approval.getStatus())) {
            return Optional.empty();
        }
        try {
            PendingToolExecution execution = mapper.readValue(approval.getPayload(), PendingToolExecution.class);
            approval.setStatus(PendingToolApproval.STATUS_RESOLVED);
            repository.save(approval);
            return Optional.of(execution);
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível recuperar a aprovação pendente.", exception);
        }
    }

    @Transactional
    public void supersedeSiblings(String runId, String retainedApprovalId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        List<PendingToolApproval> siblings = repository.findByRunIdAndStatus(runId, PendingToolApproval.STATUS_PENDING);
        siblings.stream()
                .filter(approval -> !approval.getApprovalId().equals(retainedApprovalId))
                .forEach(approval -> approval.setStatus(PendingToolApproval.STATUS_SUPERSEDED));
        repository.saveAll(siblings);
    }

    private UUID userId(ToolCall toolCall) {
        String value =
                toolCall == null ? "" : toolCall.arguments().path("_userId").asText("");
        try {
            return value.isBlank() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
