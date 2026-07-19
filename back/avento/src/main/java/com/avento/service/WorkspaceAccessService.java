package com.avento.service;

import com.avento.service.tools.ToolExecutionContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceAccessService {

    private static final String LOCAL_SCOPE = "local";

    private final Map<String, Set<Path>> workspaceRoots = new ConcurrentHashMap<>();
    private ToolExecutionContext executionContext;

    public WorkspaceAccessService() {}

    @Autowired
    public WorkspaceAccessService(ToolExecutionContext executionContext) {
        this.executionContext = executionContext;
    }

    public Path registerWorkspaceRoot(String path) {
        return registerWorkspaceRoot((UUID) null, path);
    }

    public Path registerWorkspaceRoot(UUID userId, String path) {
        Path root = normalizeExistingPath(path);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Workspace root must be an existing directory");
        }
        roots(scopeFor(userId)).add(root);
        return root;
    }

    public boolean isRegisteredRoot(Path path) {
        return authorizedRoots().contains(path);
    }

    public Path requireAuthorized(String path) {
        UUID userId =
                executionContext == null ? null : executionContext.current().userId();
        return requireAuthorized(userId, path);
    }

    public Path requireAuthorized(UUID userId, String path) {
        Path target = normalizePath(path);
        boolean allowed = authorizedRoots(userId).stream().anyMatch(target::startsWith);
        if (!allowed) {
            throw new SecurityException("Path is outside the authorized workspace roots");
        }
        return target;
    }

    public void clearUser(UUID userId) {
        if (userId == null) {
            return;
        }
        String prefix = scopeFor(userId);
        workspaceRoots.keySet().removeIf(scope -> scope.equals(prefix) || scope.startsWith(prefix + ":chat:"));
    }

    private Set<Path> authorizedRoots() {
        UUID userId =
                executionContext == null ? null : executionContext.current().userId();
        return authorizedRoots(userId);
    }

    private Set<Path> authorizedRoots(UUID userId) {
        if (userId == null) {
            return Set.copyOf(roots(LOCAL_SCOPE));
        }
        LinkedHashSet<Path> authorized = new LinkedHashSet<>(roots(scopeFor(userId)));
        if (executionContext != null && userId.equals(executionContext.current().userId())) {
            authorized.addAll(roots(executionContext.current().scopeKey()));
        }
        return Set.copyOf(authorized);
    }

    private Set<Path> roots(String scope) {
        return workspaceRoots.computeIfAbsent(scope, ignored -> ConcurrentHashMap.newKeySet());
    }

    private String scopeFor(UUID userId) {
        return userId == null ? LOCAL_SCOPE : "user:" + userId;
    }

    private Path normalizeExistingPath(String path) {
        try {
            return Paths.get(path).toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid path: " + path, e);
        }
    }

    private Path normalizePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Path is required");
        }

        Path target = Paths.get(path).toAbsolutePath().normalize();
        if (Files.exists(target)) {
            try {
                return target.toRealPath();
            } catch (IOException e) {
                throw new IllegalArgumentException("Invalid path: " + path, e);
            }
        }
        return target;
    }
}
