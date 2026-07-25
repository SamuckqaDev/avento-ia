package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.BaseResponse;
import com.avento.auth.security.AuthPrincipal;
import com.avento.service.FileBackupService;
import com.avento.service.WorkspaceAccessService;
import com.avento.service.dto.BackupEntry;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fs")
public class FileSystemController {

    private final WorkspaceAccessService workspaceAccessService;
    private final FileBackupService fileBackupService;

    public FileSystemController(WorkspaceAccessService workspaceAccessService, FileBackupService fileBackupService) {
        this.workspaceAccessService = workspaceAccessService;
        this.fileBackupService = fileBackupService;
    }

    @PostMapping("/authorize")
    public ResponseEntity<BaseResponse<Map<String, String>>> authorizeWorkspace(
            @RequestBody Map<String, String> payload, @AuthenticationPrincipal AuthPrincipal principal) {
        Path workspaceRoot = workspaceAccessService.registerWorkspaceRoot(userId(principal), payload.get("path"));
        Map<String, String> res = new HashMap<>();
        res.put("path", workspaceRoot.toString());
        return ApiResponses.ok(res);
    }

    // Lets the user grant broad, one-time access to their whole home directory instead of
    // authorizing project folders one at a time. requireAuthorized() matches by path prefix, so
    // once ~ is registered, every file tool works anywhere under it with no further prompts.
    // Still scoped to the user's own home — not /System, /etc, or other users' directories.
    @PostMapping("/authorize-home")
    public ResponseEntity<BaseResponse<Map<String, String>>> authorizeHomeDirectory(
            @AuthenticationPrincipal AuthPrincipal principal) {
        Path workspaceRoot =
                workspaceAccessService.registerWorkspaceRoot(userId(principal), System.getProperty("user.home"));
        Map<String, String> res = new HashMap<>();
        res.put("path", workspaceRoot.toString());
        return ApiResponses.ok(res);
    }

    @PostMapping("/tree")
    public ResponseEntity<BaseResponse<List<Map<String, Object>>>> getTree(
            @RequestBody Map<String, String> payload, @AuthenticationPrincipal AuthPrincipal principal) {
        String path = payload.get("path");
        File root = workspaceAccessService
                .requireAuthorized(userId(principal), path)
                .toFile();
        if (!root.exists() || !root.isDirectory()) {
            throw new RuntimeException("Invalid directory path");
        }
        return ApiResponses.ok(buildTree(root));
    }

    private List<Map<String, Object>> buildTree(File dir) {
        List<Map<String, Object>> list = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                // Ignore hidden files and common massive folders
                if (file.isHidden()
                        || file.getName().equals("node_modules")
                        || file.getName().equals(".git")
                        || file.getName().equals("target")) {
                    continue;
                }
                Map<String, Object> map = new HashMap<>();
                map.put("name", file.getName());
                map.put("path", file.getAbsolutePath());
                if (file.isDirectory()) {
                    map.put("type", "directory");
                    map.put("children", buildTree(file));
                } else {
                    map.put("type", "file");
                }
                list.add(map);
            }
        }
        // Sort: directories first, then files
        list.sort((a, b) -> {
            boolean aIsDir = a.get("type").equals("directory");
            boolean bIsDir = b.get("type").equals("directory");
            if (aIsDir && !bIsDir) return -1;
            if (!aIsDir && bIsDir) return 1;
            return ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
        });
        return list;
    }

    @PostMapping("/read")
    public ResponseEntity<BaseResponse<Map<String, String>>> readFile(
            @RequestBody Map<String, String> payload, @AuthenticationPrincipal AuthPrincipal principal)
            throws IOException {
        String path = payload.get("path");
        File file = workspaceAccessService
                .requireAuthorized(userId(principal), path)
                .toFile();
        if (!file.exists() || !file.isFile()) {
            throw new RuntimeException("Invalid file path");
        }
        String content = new String(Files.readAllBytes(file.toPath()));
        Map<String, String> res = new HashMap<>();
        res.put("content", content);
        return ApiResponses.ok(res);
    }

    @GetMapping("/pick-folder")
    public ResponseEntity<BaseResponse<Map<String, String>>> pickFolder(
            @AuthenticationPrincipal AuthPrincipal principal) {
        Map<String, String> res = new HashMap<>();
        try {
            // Usa AppleScript para abrir o diálogo de seleção de pastas nativo do Mac
            ProcessBuilder pb =
                    new ProcessBuilder("osascript", "-e", "set folderPath to POSIX path of (choose folder)");
            Process process = pb.start();

            Scanner s = new Scanner(process.getInputStream()).useDelimiter("\\A");
            String result = s.hasNext() ? s.next() : "";

            process.waitFor();

            if (result != null && !result.trim().isEmpty()) {
                Path workspaceRoot = workspaceAccessService.registerWorkspaceRoot(userId(principal), result.trim());
                res.put("path", workspaceRoot.toString());
            } else {
                res.put("path", ""); // Cancelado
            }
        } catch (Exception e) {
            e.printStackTrace();
            res.put("path", "");
        }
        return ApiResponses.ok(res);
    }

    @PostMapping("/write")
    public ResponseEntity<BaseResponse<Map<String, String>>> writeFile(
            @RequestBody Map<String, String> payload, @AuthenticationPrincipal AuthPrincipal principal)
            throws IOException {
        String path = payload.get("path");
        String content = payload.get("content");

        // Permite sobrescrever o arquivo, ou criá-lo se não existir, mas o caminho deve ser válido
        if (path == null || path.trim().isEmpty()) {
            throw new RuntimeException("Invalid file path");
        }
        if (content == null) {
            throw new IllegalArgumentException("File content is required");
        }

        Path authorizedPath = workspaceAccessService.requireAuthorized(userId(principal), path);
        File file = authorizedPath.toFile();

        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        BackupEntry backup = fileBackupService.backupBeforeWrite(authorizedPath);

        Files.write(file.toPath(), content.getBytes());

        Map<String, String> res = new HashMap<>();
        res.put("status", "success");
        res.put("backupId", backup.id());
        return ApiResponses.ok(res);
    }

    @PostMapping("/restore")
    public ResponseEntity<BaseResponse<Map<String, String>>> restoreFile(
            @RequestBody Map<String, String> payload, @AuthenticationPrincipal AuthPrincipal principal)
            throws IOException {
        String backupId = payload.get("backupId");
        BackupEntry backup = fileBackupService.getBackup(backupId);
        workspaceAccessService.requireAuthorized(userId(principal), backup.originalPath());
        fileBackupService.restore(backupId);

        Map<String, String> res = new HashMap<>();
        res.put("status", "success");
        res.put("path", backup.originalPath());
        return ApiResponses.ok(res);
    }

    private UUID userId(AuthPrincipal principal) {
        return principal == null ? null : principal.userId();
    }
}
