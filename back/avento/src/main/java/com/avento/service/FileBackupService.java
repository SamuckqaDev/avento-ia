package com.avento.service;

import com.avento.model.FileChangeBackup;
import com.avento.repository.FileChangeBackupRepository;
import com.avento.service.dto.BackupEntry;
import com.avento.service.dto.Context;
import com.avento.service.dto.DirectoryBackupEntry;
import com.avento.service.dto.RevertResult;
import com.avento.service.tools.ToolExecutionContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class FileBackupService {

    // Backing up a directory before delete_directory means copying it whole. That's fine for
    // source trees but pointless (and slow) for something like a 30k-file node_modules — past
    // this many files we skip the copy and rely on the mandatory approval step instead.
    private static final int MAX_DIRECTORY_BACKUP_FILES = 5000;

    private final Path backupDirectory;
    private final FileChangeBackupRepository backupRepository;
    private final ToolExecutionContext executionContext;
    private final Map<String, BackupEntry> backups = new ConcurrentHashMap<>();
    private final Map<String, DirectoryBackupEntry> directoryBackups = new ConcurrentHashMap<>();

    // Rollback por run: cada run acumula os backupIds dos arquivos que tocou (mais recente no topo),
    // e runOrder mantém as runs que ainda podem ser revertidas, da mais recente para a mais antiga.
    // Limitado às últimas MAX_TRACKED_RUNS para não crescer sem fim em memória/disco numa sessão longa.
    private static final int MAX_TRACKED_RUNS = 30;
    private static final int MAX_ORPHAN_BACKUPS = 100;
    private static final Duration ORPHAN_RETENTION = Duration.ofDays(3);
    private final Map<String, Deque<String>> fileBackupsByRun = new ConcurrentHashMap<>();
    private final Deque<String> runOrder = new ConcurrentLinkedDeque<>();

    @Autowired
    public FileBackupService(FileChangeBackupRepository backupRepository, ToolExecutionContext executionContext) {
        this(backupRepository, executionContext, Paths.get(System.getProperty("user.dir"), "tmp", "avento-backups"));
    }

    FileBackupService(
            FileChangeBackupRepository backupRepository, ToolExecutionContext executionContext, Path backupDirectory) {
        this.backupRepository = backupRepository;
        this.executionContext = executionContext;
        this.backupDirectory = backupDirectory.toAbsolutePath().normalize();
    }

    /** Variante que registra o backup sob a run, para permitir reverter a run inteira depois. */
    public BackupEntry backupBeforeWrite(Path targetFile, String runId) throws IOException {
        BackupEntry entry = backupBeforeWrite(targetFile);
        Context context = executionContext.current();
        if (canPersist(context, runId)) {
            persistFileBackup(context, runId, entry);
            backups.remove(entry.id());
            evictOldPersistentRuns(context);
        } else {
            trackForRun(runId, entry.id());
        }
        return entry;
    }

    private void trackForRun(String runId, String backupId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        fileBackupsByRun
                .computeIfAbsent(runId, key -> new ConcurrentLinkedDeque<>())
                .push(backupId);
        runOrder.remove(runId);
        runOrder.push(runId);
        evictOldRuns();
    }

    // Descarta as runs mais antigas (memória + arquivos de backup no disco) quando passam do teto.
    // Só afeta rollbacks muito antigos, que nao sao mais um caso de uso real.
    private void evictOldRuns() {
        while (runOrder.size() > MAX_TRACKED_RUNS) {
            String oldest = runOrder.pollLast();
            if (oldest == null) {
                return;
            }
            Deque<String> backupIds = fileBackupsByRun.remove(oldest);
            if (backupIds == null) {
                continue;
            }
            for (String backupId : backupIds) {
                BackupEntry entry = backups.remove(backupId);
                if (entry != null) {
                    try {
                        Files.deleteIfExists(Paths.get(entry.backupPath()));
                    } catch (IOException ignored) {
                        // Limpeza best-effort: um backup que nao apaga nao pode travar nada.
                    }
                }
            }
        }
    }

    /**
     * Reverte as mudanças de arquivo da run mais recente que ainda pode ser desfeita. Restaura do
     * backup mais novo para o mais antigo, de modo que o estado original (o primeiro backup de cada
     * arquivo) prevaleça. Chamar de novo reverte a run anterior.
     */
    @Transactional
    public RevertResult revertMostRecent() {
        Context context = executionContext.current();
        if (context.userId() != null && context.chatId() != null) {
            return revertMostRecent(context.userId(), context.chatId());
        }
        String runId = runOrder.poll();
        if (runId == null) {
            return new RevertResult("", 0);
        }
        Deque<String> backupIds = fileBackupsByRun.remove(runId);
        int restored = 0;
        if (backupIds != null) {
            for (String backupId : backupIds) {
                try {
                    restore(backupId);
                    restored++;
                } catch (IOException | RuntimeException ignored) {
                    // Um backup que falha ao restaurar não deve impedir os demais.
                }
            }
        }
        return new RevertResult(runId, restored);
    }

    @Transactional
    public RevertResult revertMostRecent(UUID userId, Long chatId) {
        FileChangeBackup latest = backupRepository
                .findFirstByUserIdAndChatIdAndRevertedFalseOrderByIdDesc(userId, chatId)
                .orElse(null);
        if (latest == null) {
            return new RevertResult("", 0);
        }
        List<FileChangeBackup> entries = backupRepository.findByUserIdAndChatIdAndRunIdAndRevertedFalseOrderByIdDesc(
                userId, chatId, latest.getRunId());
        int restored = 0;
        for (FileChangeBackup entry : entries) {
            try {
                if (restorePersistent(entry)) {
                    restored++;
                }
            } catch (IOException ignored) {
                // Continue restoring the remaining entries from the same run.
            }
            entry.setReverted(true);
        }
        backupRepository.saveAll(entries);
        return new RevertResult(latest.getRunId(), restored);
    }

    public BackupEntry backupBeforeWrite(Path targetFile) throws IOException {
        Files.createDirectories(backupDirectory);

        String backupId = UUID.randomUUID().toString();
        boolean existed = Files.exists(targetFile);
        Path backupPath = backupDirectory.resolve(backupId + ".bak");

        if (existed) {
            Files.copy(targetFile, backupPath);
        } else {
            Files.writeString(backupPath, "", StandardCharsets.UTF_8);
        }

        BackupEntry entry = new BackupEntry(
                backupId,
                targetFile.toAbsolutePath().normalize().toString(),
                backupPath.toString(),
                existed,
                LocalDateTime.now().toString());
        backups.put(backupId, entry);
        return entry;
    }

    public BackupEntry restore(String backupId) throws IOException {
        BackupEntry entry = getBackup(backupId);

        Path targetPath = Paths.get(entry.originalPath()).toAbsolutePath().normalize();
        Path backupPath = Paths.get(entry.backupPath()).toAbsolutePath().normalize();

        if (entry.existed()) {
            Files.createDirectories(targetPath.getParent());
            Files.copy(backupPath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(targetPath);
        }

        return entry;
    }

    public BackupEntry getBackup(String backupId) {
        BackupEntry entry = backups.get(backupId);
        if (entry == null) {
            throw new IllegalArgumentException("Backup not found");
        }
        return entry;
    }

    public DirectoryBackupEntry backupDirectoryBeforeDelete(Path targetDirectory) throws IOException {
        long fileCount;
        try (Stream<Path> stream = Files.walk(targetDirectory)) {
            fileCount = stream.filter(Files::isRegularFile).count();
        }

        String backupId = UUID.randomUUID().toString();
        String originalPath = targetDirectory.toAbsolutePath().normalize().toString();
        if (fileCount > MAX_DIRECTORY_BACKUP_FILES) {
            DirectoryBackupEntry entry = new DirectoryBackupEntry(
                    backupId,
                    originalPath,
                    null,
                    fileCount,
                    false,
                    LocalDateTime.now().toString());
            directoryBackups.put(backupId, entry);
            return entry;
        }

        Files.createDirectories(backupDirectory);
        Path backupPath = backupDirectory.resolve(backupId);
        Files.createDirectories(backupPath);
        copyDirectoryTree(targetDirectory, backupPath);

        DirectoryBackupEntry entry = new DirectoryBackupEntry(
                backupId,
                originalPath,
                backupPath.toString(),
                fileCount,
                true,
                LocalDateTime.now().toString());
        directoryBackups.put(backupId, entry);
        return entry;
    }

    public DirectoryBackupEntry backupDirectoryBeforeDelete(Path targetDirectory, String runId) throws IOException {
        DirectoryBackupEntry entry = backupDirectoryBeforeDelete(targetDirectory);
        Context context = executionContext.current();
        if (canPersist(context, runId)) {
            persistDirectoryBackup(context, runId, entry, true);
            directoryBackups.remove(entry.id());
            evictOldPersistentRuns(context);
        }
        return entry;
    }

    public void recordCreatedDirectory(Path targetDirectory, String runId) {
        Context context = executionContext.current();
        if (!canPersist(context, runId)) {
            return;
        }
        DirectoryBackupEntry entry = new DirectoryBackupEntry(
                UUID.randomUUID().toString(),
                targetDirectory.toAbsolutePath().normalize().toString(),
                null,
                0,
                true,
                LocalDateTime.now().toString());
        persistDirectoryBackup(context, runId, entry, false);
        evictOldPersistentRuns(context);
    }

    public DirectoryBackupEntry getDirectoryBackup(String backupId) {
        DirectoryBackupEntry entry = directoryBackups.get(backupId);
        if (entry == null) {
            throw new IllegalArgumentException("Directory backup not found");
        }
        return entry;
    }

    private void copyDirectoryTree(Path source, Path target) throws IOException {
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(source)) {
            paths = stream.toList();
        }
        for (Path path : paths) {
            Path destination = target.resolve(source.relativize(path).toString());
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
            } else {
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private boolean canPersist(Context context, String runId) {
        return context.userId() != null && context.chatId() != null && runId != null && !runId.isBlank();
    }

    private void persistFileBackup(Context context, String runId, BackupEntry entry) {
        FileChangeBackup backup = basePersistentBackup(context, runId, entry.id(), FileChangeBackup.TYPE_FILE);
        backup.setOriginalPath(entry.originalPath());
        backup.setBackupPath(entry.backupPath());
        backup.setExisted(entry.existed());
        backupRepository.save(backup);
    }

    private void persistDirectoryBackup(Context context, String runId, DirectoryBackupEntry entry, boolean existed) {
        FileChangeBackup backup = basePersistentBackup(context, runId, entry.id(), FileChangeBackup.TYPE_DIRECTORY);
        backup.setOriginalPath(entry.originalPath());
        backup.setBackupPath(entry.backupPath());
        backup.setExisted(existed);
        backup.setRestorable(!existed || entry.backedUp());
        backupRepository.save(backup);
    }

    private FileChangeBackup basePersistentBackup(Context context, String runId, String backupId, String type) {
        FileChangeBackup backup = new FileChangeBackup();
        backup.setBackupId(backupId);
        backup.setUserId(context.userId());
        backup.setChatId(context.chatId());
        backup.setRunId(runId);
        backup.setEntryType(type);
        return backup;
    }

    private boolean restorePersistent(FileChangeBackup entry) throws IOException {
        if (!entry.isRestorable()) {
            return false;
        }
        Path target = Paths.get(entry.getOriginalPath()).toAbsolutePath().normalize();
        if (FileChangeBackup.TYPE_DIRECTORY.equals(entry.getEntryType())) {
            if (!entry.isExisted()) {
                deleteDirectoryTree(target);
                return true;
            }
            Path source = Paths.get(entry.getBackupPath()).toAbsolutePath().normalize();
            Files.createDirectories(target);
            copyDirectoryTree(source, target);
            return true;
        }
        if (entry.isExisted()) {
            Path source = Paths.get(entry.getBackupPath()).toAbsolutePath().normalize();
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(target);
        }
        return true;
    }

    private void deleteDirectoryTree(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                    if (exception instanceof NoSuchFileException) {
                        return FileVisitResult.CONTINUE;
                    }
                    throw exception;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path currentDirectory, IOException exception)
                        throws IOException {
                    if (exception != null && !(exception instanceof NoSuchFileException)) {
                        throw exception;
                    }
                    Files.deleteIfExists(currentDirectory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (NoSuchFileException ignored) {
            // Another best-effort cleanup may remove the root between exists() and traversal.
        }
    }

    private void evictOldPersistentRuns(Context context) {
        List<FileChangeBackup> entries =
                backupRepository.findByUserIdAndChatIdAndRevertedFalseOrderByIdDesc(context.userId(), context.chatId());
        LinkedHashSet<String> runIds = new LinkedHashSet<>();
        entries.forEach(entry -> runIds.add(entry.getRunId()));
        if (runIds.size() <= MAX_TRACKED_RUNS) {
            return;
        }
        Set<String> retained = runIds.stream().limit(MAX_TRACKED_RUNS).collect(Collectors.toSet());
        List<FileChangeBackup> expired = entries.stream()
                .filter(entry -> !retained.contains(entry.getRunId()))
                .toList();
        expired.forEach(this::deletePersistentBackupFile);
        backupRepository.deleteAllInBatch(expired);
    }

    private void deletePersistentBackupFile(FileChangeBackup entry) {
        if (entry.getBackupPath() == null || entry.getBackupPath().isBlank()) {
            return;
        }
        try {
            Path path = Paths.get(entry.getBackupPath()).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                deleteDirectoryTree(path);
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Retention cleanup is best effort and must not block an agent edit.
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOrphanedBackupsOnStartup() {
        cleanupOrphanedBackups();
    }

    @Scheduled(
            initialDelayString = "${avento.backups.cleanup-delay-ms:86400000}",
            fixedDelayString = "${avento.backups.cleanup-delay-ms:86400000}")
    public void cleanupOrphanedBackupsOnSchedule() {
        cleanupOrphanedBackups();
    }

    public synchronized void cleanupOrphanedBackups() {
        try {
            if (!Files.isDirectory(backupDirectory)) {
                return;
            }
            Set<Path> referenced = backupRepository.findAll().stream()
                    .map(FileChangeBackup::getBackupPath)
                    .filter(path -> path != null && !path.isBlank())
                    .map(Paths::get)
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .collect(Collectors.toSet());
            cleanupOrphanedBackupPaths(referenced);
        } catch (IOException | RuntimeException exception) {
            log.warn("Could not clean orphaned file backups; startup will continue", exception);
        }
    }

    private void cleanupOrphanedBackupPaths(Set<Path> referenced) throws IOException {
        try (Stream<Path> stream = Files.list(backupDirectory)) {
            List<Path> candidates = stream.filter(
                            path -> !referenced.contains(path.toAbsolutePath().normalize()))
                    .sorted(Comparator.comparing(this::lastModified).reversed())
                    .toList();
            Instant cutoff = Instant.now().minus(ORPHAN_RETENTION);
            for (int index = 0; index < candidates.size(); index++) {
                Path candidate = candidates.get(index);
                if (index >= MAX_ORPHAN_BACKUPS || lastModified(candidate).isBefore(cutoff)) {
                    deleteBackupPath(candidate);
                }
            }
        }
    }

    private Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException exception) {
            return Instant.EPOCH;
        }
    }

    private void deleteBackupPath(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            deleteDirectoryTree(path);
        } else {
            Files.deleteIfExists(path);
        }
    }
}
