package com.avento.service;

import com.avento.service.dto.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class FileBackupService {

    // Backing up a directory before delete_directory means copying it whole. That's fine for
    // source trees but pointless (and slow) for something like a 30k-file node_modules — past
    // this many files we skip the copy and rely on the mandatory approval step instead.
    private static final int MAX_DIRECTORY_BACKUP_FILES = 5000;

    private final Path backupDirectory = Paths.get(System.getProperty("user.dir"), "tmp", "avento-backups");
    private final Map<String, BackupEntry> backups = new ConcurrentHashMap<>();
    private final Map<String, DirectoryBackupEntry> directoryBackups = new ConcurrentHashMap<>();

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
}
