package com.avento.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.model.FileChangeBackup;
import com.avento.repository.FileChangeBackupRepository;
import com.avento.service.dto.BackupEntry;
import com.avento.service.dto.Context;
import com.avento.service.dto.DirectoryBackupEntry;
import com.avento.service.dto.RevertResult;
import com.avento.service.tools.ToolExecutionContext;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class FileBackupServiceTest {

    @TempDir
    Path tempDir;

    private FileBackupService service() {
        return new FileBackupService(
                org.mockito.Mockito.mock(FileChangeBackupRepository.class), new ToolExecutionContext());
    }

    private FileBackupService service(Path backupDirectory, FileChangeBackupRepository repository) {
        return new FileBackupService(repository, new ToolExecutionContext(), backupDirectory);
    }

    @Test
    void springSelectsTheProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    FileChangeBackupRepository.class, () -> org.mockito.Mockito.mock(FileChangeBackupRepository.class));
            context.registerBean(ToolExecutionContext.class, ToolExecutionContext::new);
            context.register(FileBackupService.class);
            context.refresh();

            assertTrue(context.containsBean("fileBackupService"));
        }
    }

    @Test
    void restoresExistingFileContent() throws Exception {
        FileBackupService service = service();
        Path file = Files.writeString(tempDir.resolve("App.tsx"), "old");

        BackupEntry backup = service.backupBeforeWrite(file);
        Files.writeString(file, "new");
        service.restore(backup.id());

        assertEquals("old", Files.readString(file));
    }

    @Test
    void removesCreatedFileWhenRestoringNewFileBackup() throws Exception {
        FileBackupService service = service();
        Path file = tempDir.resolve("NewFile.tsx");

        BackupEntry backup = service.backupBeforeWrite(file);
        Files.writeString(file, "created");
        service.restore(backup.id());

        assertFalse(Files.exists(file));
    }

    @Test
    void backsUpDirectoryTreeWhenUnderTheFileLimit() throws Exception {
        FileBackupService service = service();
        Path directory = Files.createDirectory(tempDir.resolve("project"));
        Files.writeString(directory.resolve("a.txt"), "a-content");
        Path nested = Files.createDirectory(directory.resolve("nested"));
        Files.writeString(nested.resolve("b.txt"), "b-content");

        DirectoryBackupEntry backup = service.backupDirectoryBeforeDelete(directory);

        assertTrue(backup.backedUp());
        assertEquals(2, backup.fileCount());
        assertEquals("a-content", Files.readString(Path.of(backup.backupPath()).resolve("a.txt")));
        assertEquals("b-content", Files.readString(Path.of(backup.backupPath()).resolve("nested/b.txt")));
    }

    @Test
    void skipsBackupWhenDirectoryHasTooManyFiles() throws Exception {
        FileBackupService service = service();
        Path directory = Files.createDirectory(tempDir.resolve("huge"));
        Field maxFilesField = FileBackupService.class.getDeclaredField("MAX_DIRECTORY_BACKUP_FILES");
        maxFilesField.setAccessible(true);
        int maxFiles = maxFilesField.getInt(null);
        for (int i = 0; i <= maxFiles; i++) {
            Files.createFile(directory.resolve("file" + i + ".txt"));
        }

        DirectoryBackupEntry backup = service.backupDirectoryBeforeDelete(directory);

        assertFalse(backup.backedUp());
        assertNull(backup.backupPath());
    }

    @Test
    void revertsAllFileChangesOfTheMostRecentRunToTheOriginalState(@TempDir Path dir) throws Exception {
        FileBackupService service = service();
        Path existing = dir.resolve("a.txt");
        Files.writeString(existing, "original");
        Path created = dir.resolve("b.txt");

        service.backupBeforeWrite(existing, "R1");
        Files.writeString(existing, "edit1");
        service.backupBeforeWrite(existing, "R1");
        Files.writeString(existing, "edit2");
        service.backupBeforeWrite(created, "R1");
        Files.writeString(created, "novo");

        RevertResult result = service.revertMostRecent();

        assertEquals("R1", result.runId());
        assertEquals(3, result.filesRestored());
        assertEquals("original", Files.readString(existing));
        assertFalse(Files.exists(created));
    }

    @Test
    void revertsRunsFromMostRecentToOldestOnSuccessiveCalls(@TempDir Path dir) throws Exception {
        FileBackupService service = service();
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "v0");

        service.backupBeforeWrite(file, "R1");
        Files.writeString(file, "v1");
        service.backupBeforeWrite(file, "R2");
        Files.writeString(file, "v2");

        assertEquals("R2", service.revertMostRecent().runId());
        assertEquals("v1", Files.readString(file));
        assertEquals("R1", service.revertMostRecent().runId());
        assertEquals("v0", Files.readString(file));
    }

    @Test
    void reportsNothingToRevertWhenNoTrackedChanges() {
        assertEquals(0, service().revertMostRecent().filesRestored());
    }

    @Test
    void evictsOldRunsBeyondTheCapAndDeletesTheirBackupFiles(@TempDir Path dir) throws Exception {
        FileBackupService service = service();
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "v0");

        // 40 runs > o teto de 30: as mais antigas devem ser descartadas.
        for (int i = 0; i < 40; i++) {
            service.backupBeforeWrite(file, "R" + i);
            Files.writeString(file, "v" + (i + 1));
        }

        // Só as 30 mais recentes revertem; depois disso, nada mais.
        int reverts = 0;
        while (service.revertMostRecent().filesRestored() > 0) {
            reverts++;
        }
        assertEquals(30, reverts);
    }

    @Test
    void persistentRunCanBeRestoredAfterServiceRestart(@TempDir Path dir) throws Exception {
        FileChangeBackupRepository repository = org.mockito.Mockito.mock(FileChangeBackupRepository.class);
        ToolExecutionContext context = new ToolExecutionContext();
        AtomicReference<FileChangeBackup> stored = new AtomicReference<>();
        org.mockito.Mockito.when(repository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    FileChangeBackup backup = invocation.getArgument(0);
                    backup.setId(1L);
                    stored.set(backup);
                    return backup;
                });
        org.mockito.Mockito.when(repository.findByUserIdAndChatIdAndRevertedFalseOrderByIdDesc(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of());
        UUID userId = UUID.randomUUID();
        Path file = Files.writeString(dir.resolve("persistent.txt"), "before");
        FileBackupService firstProcess = new FileBackupService(repository, context);

        context.call(new Context(userId, 22L, "run_persisted"), () -> {
            firstProcess.backupBeforeWrite(file, "run_persisted");
            return null;
        });
        Files.writeString(file, "after");
        org.mockito.Mockito.when(repository.findFirstByUserIdAndChatIdAndRevertedFalseOrderByIdDesc(userId, 22L))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        org.mockito.Mockito.when(repository.findByUserIdAndChatIdAndRunIdAndRevertedFalseOrderByIdDesc(
                        userId, 22L, "run_persisted"))
                .thenAnswer(invocation -> List.of(stored.get()));

        FileBackupService restartedProcess = new FileBackupService(repository, new ToolExecutionContext());
        RevertResult result = restartedProcess.revertMostRecent(userId, 22L);

        assertEquals(1, result.filesRestored());
        assertEquals("before", Files.readString(file));
    }

    @Test
    void concurrentOrphanCleanupDoesNotFailWhenTheSameTreeDisappears() throws Exception {
        FileChangeBackupRepository repository = org.mockito.Mockito.mock(FileChangeBackupRepository.class);
        org.mockito.Mockito.when(repository.findAll()).thenReturn(List.of());
        Path backupDirectory = Files.createDirectory(tempDir.resolve("backups"));
        Path orphan = Files.createDirectory(backupDirectory.resolve("orphan"));
        Path nested = Files.createDirectory(orphan.resolve("nested"));
        for (int index = 0; index < 200; index++) {
            Files.writeString(nested.resolve("file-" + index + ".txt"), "backup");
        }
        Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minusSeconds(4 * 24 * 60 * 60)));
        FileBackupService service = service(backupDirectory, repository);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                await(start);
                service.cleanupOrphanedBackups();
            });
            Future<?> second = executor.submit(() -> {
                await(start);
                service.cleanupOrphanedBackups();
            });
            start.countDown();
            first.get();
            second.get();
        }

        assertFalse(Files.exists(orphan));
    }

    @Test
    void startupContinuesWhenOrphanMaintenanceFails() throws Exception {
        FileChangeBackupRepository repository = org.mockito.Mockito.mock(FileChangeBackupRepository.class);
        org.mockito.Mockito.when(repository.findAll()).thenThrow(new IllegalStateException("database unavailable"));
        Path backupDirectory = Files.createDirectory(tempDir.resolve("failing-backups"));
        FileBackupService service = service(backupDirectory, repository);

        assertDoesNotThrow(service::cleanupOrphanedBackupsOnStartup);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to start backup cleanup", exception);
        }
    }
}
