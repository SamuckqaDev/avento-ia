package com.avento.service;

import com.avento.model.AgentPlan;
import com.avento.model.FileChangeBackup;
import com.avento.repository.AgentPlanRepository;
import com.avento.repository.AgentTaskRepository;
import com.avento.repository.AgentTimelineEventRepository;
import com.avento.repository.FileChangeBackupRepository;
import com.avento.repository.PendingToolApprovalRepository;
import com.avento.repository.TokenUsageRepository;
import com.avento.service.dto.ResidueDeletionResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Removes everything a chat leaves behind outside its own messages and media: agent timeline,
 * pending approvals, plans and tasks, token accounting and file-change backups (rows and .bak
 * files). Deleting a chat must not leave any trace of it on the machine.
 */
@Service
public class ChatPurgeService {

    private static final Logger logger = LoggerFactory.getLogger(ChatPurgeService.class);

    private final AgentTimelineEventRepository timelineEventRepository;
    private final PendingToolApprovalRepository approvalRepository;
    private final AgentPlanRepository planRepository;
    private final AgentTaskRepository taskRepository;
    private final TokenUsageRepository tokenUsageRepository;
    private final FileChangeBackupRepository backupRepository;

    public ChatPurgeService(
            AgentTimelineEventRepository timelineEventRepository,
            PendingToolApprovalRepository approvalRepository,
            AgentPlanRepository planRepository,
            AgentTaskRepository taskRepository,
            TokenUsageRepository tokenUsageRepository,
            FileChangeBackupRepository backupRepository) {
        this.timelineEventRepository = timelineEventRepository;
        this.approvalRepository = approvalRepository;
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
        this.tokenUsageRepository = tokenUsageRepository;
        this.backupRepository = backupRepository;
    }

    public ResidueDeletionResult purgeForChat(Long chatId, UUID userId) {
        if (chatId == null || userId == null) {
            return ResidueDeletionResult.empty();
        }

        FileDeletion backups = deleteBackups(chatId, userId);
        long rows = backups.rows()
                + timelineEventRepository.deleteByChatIdAndUserId(chatId, userId)
                + approvalRepository.deleteByChatIdAndUserId(chatId, userId)
                + tokenUsageRepository.deleteByChatIdAndUserId(chatId, userId)
                + deletePlans(chatId, userId);

        return new ResidueDeletionResult(rows, backups.deletedFiles(), backups.failedFiles());
    }

    private long deletePlans(Long chatId, UUID userId) {
        List<AgentPlan> plans = planRepository.findByChatIdAndUserId(chatId, userId);
        if (plans.isEmpty()) {
            return 0;
        }
        List<Long> planIds = plans.stream().map(AgentPlan::getId).toList();
        long tasks = taskRepository.deleteByPlanIdInAndUserId(planIds, userId);
        planRepository.deleteAllInBatch(plans);
        return tasks + plans.size();
    }

    private FileDeletion deleteBackups(Long chatId, UUID userId) {
        List<FileChangeBackup> backups = backupRepository.findByUserIdAndChatId(userId, chatId);
        if (backups.isEmpty()) {
            return new FileDeletion(0, 0, 0);
        }
        int deleted = 0;
        int failed = 0;
        for (FileChangeBackup backup : backups) {
            String backupPath = backup.getBackupPath();
            if (backupPath == null || backupPath.isBlank()) {
                continue;
            }
            // Best effort: a backup file that cannot be removed must not keep the chat alive.
            try {
                if (deleteRecursively(Paths.get(backupPath))) {
                    deleted++;
                }
            } catch (IOException | RuntimeException exception) {
                failed++;
                logger.warn("Could not delete backup {} of chat {}", backupPath, chatId, exception);
            }
        }
        backupRepository.deleteAllInBatch(backups);
        return new FileDeletion(backups.size(), deleted, failed);
    }

    // Directory backups (entryType "directory") point to a folder, not a single .bak file.
    private boolean deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return false;
        }
        if (Files.isDirectory(path)) {
            try (var entries = Files.walk(path)) {
                for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(entry);
                }
            }
            return true;
        }
        return Files.deleteIfExists(path);
    }

    private record FileDeletion(long rows, int deletedFiles, int failedFiles) {}
}
