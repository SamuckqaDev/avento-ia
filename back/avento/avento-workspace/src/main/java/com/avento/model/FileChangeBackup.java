package com.avento.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "file_change_backups",
        indexes = {
            @Index(name = "idx_file_backup_scope", columnList = "user_id,chat_id,reverted,id"),
            @Index(name = "idx_file_backup_run", columnList = "user_id,chat_id,run_id,id")
        })
@Getter
@Setter
@NoArgsConstructor
public class FileChangeBackup {

    public static final String TYPE_FILE = "FILE";
    public static final String TYPE_DIRECTORY = "DIRECTORY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "backup_id", nullable = false, unique = true, length = 36)
    private String backupId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "run_id", nullable = false, length = 80)
    private String runId;

    @Column(name = "entry_type", nullable = false, length = 16)
    private String entryType;

    @Column(name = "original_path", nullable = false, columnDefinition = "TEXT")
    private String originalPath;

    @Column(name = "backup_path", columnDefinition = "TEXT")
    private String backupPath;

    @Column(nullable = false)
    private boolean existed;

    @Column(nullable = false)
    private boolean restorable = true;

    @Column(nullable = false)
    private boolean reverted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
