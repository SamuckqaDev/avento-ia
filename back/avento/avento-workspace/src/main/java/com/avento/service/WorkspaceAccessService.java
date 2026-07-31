package com.avento.service;

import com.avento.service.tools.ToolExecutionContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceAccessService {

    private static final String LOCAL_SCOPE = "local";
    private static final String WORKSPACE_PLACEHOLDER_PREFIX = "/workspace/";

    private final Map<String, Set<Path>> workspaceRoots = new ConcurrentHashMap<>();
    private ToolExecutionContext executionContext;

    @Value("${avento.workspace.default-root:}")
    private String defaultWorkspaceRoot;

    // Autoriza o diretório de trabalho do processo quando nenhuma raiz foi registrada. Só existe
    // como escape manual de depuração; em uso normal a pasta vem da conversa ou da tarefa.
    @Value("${avento.workspace.allow-working-directory-fallback:false}")
    private boolean allowWorkingDirectoryFallback;

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
        Set<Path> roots = authorizedRoots(userId);
        Path primaryRoot = roots.isEmpty() ? null : roots.iterator().next();

        // O modelo escreve caminhos-marcador em vez do caminho real com frequência ("." , "/workspace").
        // Resolvê-los para a raiz do projeto é correto — resolver "." pelo diretório do processo
        // apontaria para o código do próprio Avento.
        if (primaryRoot != null && isWorkspacePlaceholder(path)) {
            return primaryRoot;
        }
        // "/workspace/src/App.tsx" -> "<raiz>/src/App.tsx". Antes o prefixo inteiro virava a raiz,
        // então uma escrita em /workspace/src/App.tsx caía na própria pasta raiz.
        if (primaryRoot != null && path != null && path.startsWith(WORKSPACE_PLACEHOLDER_PREFIX)) {
            Path resolved = primaryRoot
                    .resolve(path.substring(WORKSPACE_PLACEHOLDER_PREFIX.length()))
                    .normalize();
            if (!resolved.startsWith(primaryRoot)) {
                throw new SecurityException("Path escapes the workspace root: " + path);
            }
            return resolved;
        }

        Path target = normalizePath(path);
        // Somente descendentes de uma raiz autorizada. A checagem inversa (root.startsWith(target))
        // autorizava qualquer PAI da raiz — com raiz /Users/x/projeto, "/Users" e "/" passavam.
        boolean allowed = roots.stream().anyMatch(target::startsWith);
        if (!allowed) {
            // Negar é o contrato deste método. Devolver a raiz aqui transformava um caminho fora do
            // workspace numa operação sobre a raiz: delete_directory("/fora") apagava o projeto.
            throw new SecurityException("Path is outside the authorized workspace roots: " + path);
        }
        return target;
    }

    private boolean isWorkspacePlaceholder(String path) {
        return path == null
                || path.isBlank()
                || ".".equals(path)
                || "./".equals(path)
                || "/workspace".equals(path)
                || "/workspace/".equals(path);
    }

    public void clearUser(UUID userId) {
        if (userId == null) {
            return;
        }
        String prefix = scopeFor(userId);
        workspaceRoots.keySet().removeIf(scope -> scope.equals(prefix) || scope.startsWith(prefix + ":chat:"));
    }

    public Set<Path> authorizedRoots() {
        UUID userId =
                executionContext == null ? null : executionContext.current().userId();
        return authorizedRoots(userId);
    }

    private Set<Path> authorizedRoots(UUID userId) {
        LinkedHashSet<Path> authorized = new LinkedHashSet<>();
        if (defaultWorkspaceRoot != null && !defaultWorkspaceRoot.isBlank()) {
            try {
                Path defaultRoot =
                        Paths.get(defaultWorkspaceRoot).toAbsolutePath().normalize();
                if (Files.exists(defaultRoot)) {
                    authorized.add(defaultRoot.toRealPath());
                }
            } catch (IOException ignored) {
            }
        }
        if (userId == null) {
            authorized.addAll(roots(LOCAL_SCOPE));
        } else {
            authorized.addAll(roots(scopeFor(userId)));
            if (executionContext != null
                    && userId.equals(executionContext.current().userId())) {
                authorized.addAll(roots(executionContext.current().scopeKey()));
            }
        }
        if (authorized.isEmpty() && allowWorkingDirectoryFallback) {
            // Desligado por padrão: o diretório do processo é o código-fonte do próprio Avento, e
            // autorizá-lo em silêncio deixou uma tarefa autônoma escrever arquivos dentro do repo.
            try {
                Path userDir = Paths.get(System.getProperty("user.dir"))
                        .toAbsolutePath()
                        .normalize();
                if (Files.exists(userDir)) {
                    authorized.add(userDir.toRealPath());
                }
            } catch (IOException ignored) {
            }
        }
        // Ordem de inserção preservada: requireAuthorized resolve "." e "/workspace" pela PRIMEIRA
        // raiz. Set.copyOf tem ordem não especificada, então com dois projetos abertos o marcador
        // caía num projeto arbitrário, podendo mudar entre execuções.
        return Collections.unmodifiableSet(authorized);
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
        return realPathOfFutureFile(target, path);
    }

    /**
     * O caminho canônico de um arquivo que ainda NÃO existe.
     *
     * <p>Devolver só {@code toAbsolutePath().normalize()} aqui quebrava dos dois lados, porque a
     * raiz é registrada com {@code toRealPath()} e um link no meio do caminho nunca casava:
     *
     * <ul>
     *   <li>criar arquivo num workspace alcançado por link era RECUSADO como se estivesse fora
     *       dele — no macOS {@code /tmp} e {@code /var} são links, então todo projeto ali dentro
     *       podia ser lido mas não escrito;
     *   <li>pior, um link DENTRO do workspace apontando para fora era ACEITO: o caminho textual
     *       começava com a raiz, e a escrita ia parar do lado de fora.
     * </ul>
     *
     * <p>Resolver o ancestral existente mais próximo e reanexar o resto acerta os dois: o destino
     * passa a ser comparado por onde ele realmente vai cair.
     */
    private Path realPathOfFutureFile(Path target, String originalPath) {
        Path existingAncestor = target.getParent();
        while (existingAncestor != null && !Files.exists(existingAncestor)) {
            existingAncestor = existingAncestor.getParent();
        }
        if (existingAncestor == null) {
            return target;
        }
        try {
            Path realAncestor = existingAncestor.toRealPath();
            return realAncestor.resolve(existingAncestor.relativize(target)).normalize();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid path: " + originalPath, e);
        }
    }
}
