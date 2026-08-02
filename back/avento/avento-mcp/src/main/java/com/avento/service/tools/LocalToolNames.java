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
            "terminal_stop",
            // Local tools that had a dispatch case and were exposed to the model but were missing from
            // this router allow-list, so every call returned "Tool not found or server disconnected".
            "find_symbol",
            "verify_project",
            "revert_changes",
            "remember",
            "create_skill",
            "list_skills",
            "delete_skill",
            // Progressive tool discovery: the model searches the lightweight capability catalog and
            // activates only the schemas it needs for the current run (keeps num_ctx lean on 16GB).
            "search_capabilities",
            "activate_tools",
            // Semantic/keyword retrieval over the connected workspace source files.
            "search_code",
            // Agendamento autonomo de tarefas repetitivas e rotinas na agenda do Cowork
            "schedule_task");

    private LocalToolNames() {}
}
