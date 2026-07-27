package com.avento.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceAccessServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void authorizesPathsInsideRegisteredWorkspace() throws Exception {
        WorkspaceAccessService service = new WorkspaceAccessService();
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path file = Files.writeString(workspace.resolve("App.java"), "class App {}");

        service.registerWorkspaceRoot(workspace.toString());

        assertEquals(file.toRealPath(), service.requireAuthorized(file.toString()));
    }

    @Test
    void rejectsPathsOutsideRegisteredWorkspace() throws Exception {
        WorkspaceAccessService service = new WorkspaceAccessService();
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "secret");

        service.registerWorkspaceRoot(workspace.toString());

        assertThrows(SecurityException.class, () -> service.requireAuthorized(outside.toString()));
    }

    @Test
    void rejectsMissingWorkspaceRoot() {
        WorkspaceAccessService service = new WorkspaceAccessService();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.registerWorkspaceRoot(tempDir.resolve("missing").toString()));
    }

    @Test
    void isolatesWorkspaceRootsByUser() throws Exception {
        WorkspaceAccessService service = new WorkspaceAccessService();
        UUID owner = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        Path workspace = Files.createDirectory(tempDir.resolve("private-workspace"));
        Path file = Files.writeString(workspace.resolve("secret.txt"), "private");

        service.registerWorkspaceRoot(owner, workspace.toString());

        assertEquals(file.toRealPath(), service.requireAuthorized(owner, file.toString()));
        assertThrows(SecurityException.class, () -> service.requireAuthorized(otherUser, file.toString()));
    }
}
