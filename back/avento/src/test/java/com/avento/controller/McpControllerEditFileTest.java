package com.avento.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class McpControllerEditFileTest {

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
    void replacesUniqueOccurrence() throws Exception {
        WorkspaceAccessService workspaceAccessService = new WorkspaceAccessService();
        workspaceAccessService.registerWorkspaceRoot(tempDir.toString());
        McpController controller = newController(workspaceAccessService);

        Path file = tempDir.resolve("App.tsx");
        Files.writeString(file, "const title = 'old';\nconst other = 'old-other';\n");

        Map<String, Object> payload = new HashMap<>();
        payload.put("path", file.toString());
        payload.put("old_string", "const title = 'old';");
        payload.put("new_string", "const title = 'new';");

        JsonNode result = controller.executeToolInternal("edit_file", payload);

        assertEquals("success", result.get("status").asText());
        assertEquals(1, result.get("replacements").asInt());
        assertTrue(result.has("backupId"));
        String updated = Files.readString(file);
        assertTrue(updated.contains("const title = 'new';"));
        assertTrue(updated.contains("const other = 'old-other';"));
    }

    @Test
    void failsWhenOldStringNotFound() throws Exception {
        WorkspaceAccessService workspaceAccessService = new WorkspaceAccessService();
        workspaceAccessService.registerWorkspaceRoot(tempDir.toString());
        McpController controller = newController(workspaceAccessService);

        Path file = tempDir.resolve("App.tsx");
        Files.writeString(file, "const title = 'old';\n");

        Map<String, Object> payload = new HashMap<>();
        payload.put("path", file.toString());
        payload.put("old_string", "does-not-exist");
        payload.put("new_string", "new");

        JsonNode result = controller.executeToolInternal("edit_file", payload);

        assertTrue(result.has("error"));
        assertTrue(Files.readString(file).contains("const title = 'old';"));
    }

    @Test
    void failsWhenOldStringIsNotUniqueWithoutReplaceAll() throws Exception {
        WorkspaceAccessService workspaceAccessService = new WorkspaceAccessService();
        workspaceAccessService.registerWorkspaceRoot(tempDir.toString());
        McpController controller = newController(workspaceAccessService);

        Path file = tempDir.resolve("App.tsx");
        Files.writeString(file, "repeat\nrepeat\n");

        Map<String, Object> payload = new HashMap<>();
        payload.put("path", file.toString());
        payload.put("old_string", "repeat");
        payload.put("new_string", "changed");

        JsonNode result = controller.executeToolInternal("edit_file", payload);

        assertTrue(result.has("error"));
        assertTrue(result.get("error").asText().contains("2 locations"));
        assertEquals("repeat\nrepeat\n", Files.readString(file));
    }

    @Test
    void replacesAllOccurrencesWhenReplaceAllIsTrue() throws Exception {
        WorkspaceAccessService workspaceAccessService = new WorkspaceAccessService();
        workspaceAccessService.registerWorkspaceRoot(tempDir.toString());
        McpController controller = newController(workspaceAccessService);

        Path file = tempDir.resolve("App.tsx");
        Files.writeString(file, "repeat\nrepeat\n");

        Map<String, Object> payload = new HashMap<>();
        payload.put("path", file.toString());
        payload.put("old_string", "repeat");
        payload.put("new_string", "changed");
        payload.put("replace_all", true);

        JsonNode result = controller.executeToolInternal("edit_file", payload);

        assertEquals("success", result.get("status").asText());
        assertEquals(2, result.get("replacements").asInt());
        assertEquals("changed\nchanged\n", Files.readString(file));
    }

    @Test
    void rejectsIdenticalOldAndNewString() throws Exception {
        WorkspaceAccessService workspaceAccessService = new WorkspaceAccessService();
        workspaceAccessService.registerWorkspaceRoot(tempDir.toString());
        McpController controller = newController(workspaceAccessService);

        Path file = tempDir.resolve("App.tsx");
        Files.writeString(file, "same\n");

        Map<String, Object> payload = new HashMap<>();
        payload.put("path", file.toString());
        payload.put("old_string", "same");
        payload.put("new_string", "same");

        JsonNode result = controller.executeToolInternal("edit_file", payload);

        assertTrue(result.has("error"));
    }

    @Test
    void rejectsPathOutsideAuthorizedWorkspace() throws Exception {
        WorkspaceAccessService workspaceAccessService = new WorkspaceAccessService();
        McpController controller = newController(workspaceAccessService);

        Path file = tempDir.resolve("Outside.tsx");
        Files.writeString(file, "const a = 1;");

        Map<String, Object> payload = new HashMap<>();
        payload.put("path", file.toString());
        payload.put("old_string", "const a = 1;");
        payload.put("new_string", "const a = 2;");

        assertThrows(SecurityException.class, () -> controller.executeToolInternal("edit_file", payload));
    }
}
