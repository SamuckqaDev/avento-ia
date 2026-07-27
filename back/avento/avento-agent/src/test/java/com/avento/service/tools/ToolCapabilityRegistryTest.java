package com.avento.service.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolCapabilityRegistryTest {

    private final ToolCapabilityRegistry registry = new ToolCapabilityRegistry();

    @Test
    void marksDangerousToolsAsApprovalRequired() {
        assertTrue(registry.requiresApproval("write_file"));
        assertTrue(registry.requiresApproval("edit_file"));
        assertTrue(registry.requiresApproval("delete_file"));
        assertTrue(registry.requiresApproval("terminal_start"));
        assertTrue(registry.requiresApproval("close_app"));
        assertTrue(registry.requiresApproval("connect_mcp_server"));
    }

    @Test
    void unregisteredToolsRequireApprovalByDefault() {
        assertTrue(registry.requiresApproval("git_commit"));
        assertTrue(registry.requiresApproval("docker_run"));
        assertTrue(registry.requiresApproval("some_external_mcp_tool_never_seen_before"));
    }

    @Test
    void allowsReadOnlyToolsWithoutApproval() {
        assertFalse(registry.requiresApproval("directory_tree"));
        assertFalse(registry.requiresApproval("read_file"));
        assertFalse(registry.requiresApproval("read_document"));
        assertFalse(registry.requiresApproval("list_mcp_servers"));
        assertFalse(registry.requiresApproval("terminal_logs"));
        assertFalse(registry.requiresApproval("list_macos_apps"));
    }

    @Test
    void onlyWhitelistedToolsCanRunDirectly() {
        assertTrue(registry.canExecuteDirectly("open_browser_tab"));
        assertTrue(registry.canExecuteDirectly("list_macos_apps"));
        assertFalse(registry.canExecuteDirectly("close_browser_tab"));
        assertFalse(registry.canExecuteDirectly("terminal_run"));
    }
}
