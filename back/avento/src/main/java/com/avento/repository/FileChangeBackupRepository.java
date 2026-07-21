package com.avento.repository;

import com.avento.model.FileChangeBackup;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileChangeBackupRepository extends JpaRepository<FileChangeBackup, Long> {

    Optional<FileChangeBackup> findFirstByUserIdAndChatIdAndRevertedFalseOrderByIdDesc(UUID userId, Long chatId);

    List<FileChangeBackup> findByUserIdAndChatIdAndRunIdAndRevertedFalseOrderByIdDesc(
            UUID userId, Long chatId, String runId);

    List<FileChangeBackup> findByUserIdAndChatIdAndRevertedFalseOrderByIdDesc(UUID userId, Long chatId);
}
