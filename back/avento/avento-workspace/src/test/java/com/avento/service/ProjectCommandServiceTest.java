package com.avento.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.avento.service.dto.ProjectCommandRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectCommandServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsUnapprovedNpmScript() throws Exception {
        WorkspaceAccessService workspaceAccessService = new WorkspaceAccessService();
        ProjectCommandService service = new ProjectCommandService(workspaceAccessService);
        Path project = Files.createDirectory(tempDir.resolve("app"));
        workspaceAccessService.registerWorkspaceRoot(project.toString());

        assertThrows(
                SecurityException.class,
                () -> service.run(new ProjectCommandRequest(project.toString(), "npm", "install")));
    }

    @Test
    void rejectsUnknownRunner() throws Exception {
        WorkspaceAccessService workspaceAccessService = new WorkspaceAccessService();
        ProjectCommandService service = new ProjectCommandService(workspaceAccessService);
        Path project = Files.createDirectory(tempDir.resolve("app"));
        workspaceAccessService.registerWorkspaceRoot(project.toString());

        assertThrows(
                SecurityException.class,
                () -> service.run(new ProjectCommandRequest(project.toString(), "bash", "test")));
    }

    @Test
    void rejectsProjectCommandFromAnotherUser() throws Exception {
        WorkspaceAccessService workspaceAccessService = new WorkspaceAccessService();
        ProjectCommandService service = new ProjectCommandService(workspaceAccessService);
        UUID owner = UUID.randomUUID();
        Path project = Files.createDirectory(tempDir.resolve("owned-app"));
        workspaceAccessService.registerWorkspaceRoot(owner, project.toString());

        assertThrows(
                SecurityException.class,
                () -> service.run(
                        UUID.randomUUID(), new ProjectCommandRequest(project.toString(), "npm", "typecheck")));
    }
}
