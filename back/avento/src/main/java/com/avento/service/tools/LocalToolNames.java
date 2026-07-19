package com.avento.service.tools;

import java.util.Set;

public final class LocalToolNames {

    public static final Set<String> ALL = Set.of(
            "directory_tree",
            "read_file",
            "read_document",
            "list_mcp_servers",
            "connect_mcp_server",
            "disconnect_mcp_server",
            "write_file",
            "edit_file",
            "delete_file",
            "delete_directory",
            "create_directory",
            "search_files",
            "create_vite_project",
            "list_macos_apps",
            "open_app",
            "close_app",
            "open_browser_tab",
            "close_browser_tab",
            "open_url",
            "open_path",
            "reveal_in_finder",
            "run_shortcut",
            "capture_screen",
            "generate_image",
            "generate_video",
            "generate_pdf",
            "terminal_run",
            "terminal_start",
            "terminal_list",
            "terminal_logs",
            "terminal_stop");

    private LocalToolNames() {}
}
