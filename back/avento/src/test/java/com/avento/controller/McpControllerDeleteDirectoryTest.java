package com.avento.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.service.FileBackupService;
import com.avento.service.WorkspaceAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpControllerDeleteDirectoryTest {

    @TempDir
    Path tempDir;

    private McpController newController(WorkspaceAccessService workspaceAccessService) throws Exception {
        McpController controller = new McpController();
        setField(controller, "workspaceAccessService", workspaceAccessService);
        setField(controller, "fileBackupService", new FileBackupService());
        return controller;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = McpController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void deletesDirectoryTreeWithBackup() throws Exception {
        WorkspaceAccessService workspaceAccessService = new WorkspaceAccessService();
        workspaceAccessService.registerWorkspaceRoot(tempDir.toString());
        McpController controller = newController(workspaceAccessService);

        Path project = Files.createDirectory(tempDir.resolve("scratch-project"));
        Files.writeString(project.resolve("package.json"), "{}");
        Path nested = Files.createDirectory(project.resolve("src"));
        Files.writeString(nested.resolve("main.ts"), "export {};");

        Map<String, Object> payload = new HashMap<>();
        payload.put("path", project.toString());

        JsonNode result = controller.executeToolInternal("delete_directory", payload);

        assertEquals("deleted", result.get("status").asText());
        assertTrue(result.get("backedUp").asBoolean());
        assertEquals(2, result.get("fileCount").asInt());
        assertTrue(result.has("backupId"));
        assertFalse(Files.exists(project));
    }

    @Test
    void refusesWhenPathIsNotADirectory() throws Exception {
        WorkspaceAccessService workspaceAccessService = new WorkspaceAccessService();
        workspaceAccessService.registerWorkspaceRoot(tempDir.toString());
        McpController controller = newController(workspaceAccessService);

        Path file = tempDir.resolve("App.tsx");
        Files.writeString(file, "const a = 1;");

        Map<String, Object> payload = new HashMap<>();
        payload.put("path", file.toString());

        JsonNode result = controller.executeToolInternal("delete_directory", payload);

        assertTrue(result.has("error"));
        assertTrue(Files.exists(file));
    }

    @Test
    void refusesToDeleteAWorkspaceRootItself() throws Exception {
        WorkspaceAccessService workspaceAccessService = new WorkspaceAccessService();
        workspaceAccessService.registerWorkspaceRoot(tempDir.toString());
        McpController controller = newController(workspaceAccessService);

        Map<String, Object> payload = new HashMap<>();
        payload.put("path", tempDir.toString());

        JsonNode result = controller.executeToolInternal("delete_directory", payload);

        assertTrue(result.has("error"));
        assertTrue(Files.exists(tempDir));
    }

    @Test
    void rejectsPathOutsideAuthorizedWorkspace() throws Exception {
        WorkspaceAccessService workspaceAccessService = new WorkspaceAccessService();
        McpController controller = newController(workspaceAccessService);

        Path project = Files.createDirectory(tempDir.resolve("outside-project"));

        Map<String, Object> payload = new HashMap<>();
        payload.put("path", project.toString());

        assertThrows(SecurityException.class, () -> controller.executeToolInternal("delete_directory", payload));
    }
}
