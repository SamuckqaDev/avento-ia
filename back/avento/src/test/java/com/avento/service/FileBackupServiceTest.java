package com.avento.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.service.dto.BackupEntry;
import com.avento.service.dto.DirectoryBackupEntry;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBackupServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void restoresExistingFileContent() throws Exception {
        FileBackupService service = new FileBackupService();
        Path file = Files.writeString(tempDir.resolve("App.tsx"), "old");

        BackupEntry backup = service.backupBeforeWrite(file);
        Files.writeString(file, "new");
        service.restore(backup.id());

        assertEquals("old", Files.readString(file));
    }

    @Test
    void removesCreatedFileWhenRestoringNewFileBackup() throws Exception {
        FileBackupService service = new FileBackupService();
        Path file = tempDir.resolve("NewFile.tsx");

        BackupEntry backup = service.backupBeforeWrite(file);
        Files.writeString(file, "created");
        service.restore(backup.id());

        assertFalse(Files.exists(file));
    }

    @Test
    void backsUpDirectoryTreeWhenUnderTheFileLimit() throws Exception {
        FileBackupService service = new FileBackupService();
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
        FileBackupService service = new FileBackupService();
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
}
