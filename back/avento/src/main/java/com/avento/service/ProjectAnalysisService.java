package com.avento.service;

import com.avento.service.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ProjectAnalysisService {

    private static final int MAX_FILES_TO_SCAN = 12000;
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git",
            "node_modules",
            "target",
            "dist",
            "build",
            ".next",
            ".expo",
            ".venv",
            "venv",
            ".idea",
            ".gradle",
            "coverage",
            ".dart_tool",
            "tmp",
            "logs",
            "piper_tts",
            "whisper.cpp");

    private final WorkspaceAccessService workspaceAccessService;
    private final ObjectMapper mapper;

    public ProjectAnalysisService(WorkspaceAccessService workspaceAccessService, ObjectMapper mapper) {
        this.workspaceAccessService = workspaceAccessService;
        this.mapper = mapper;
    }

    public ProjectAnalysis analyze(String path) {
        return analyze(null, path);
    }

    public ProjectAnalysis analyze(UUID userId, String path) {
        Path root = userId == null
                ? workspaceAccessService.requireAuthorized(path)
                : workspaceAccessService.requireAuthorized(userId, path);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Project path must be a directory");
        }

        ScanState state = scan(root);
        Set<String> technologies = detectTechnologies(root, state);
        List<ProjectScript> scripts = detectScripts(root, state);
        List<String> entrypoints = detectEntrypoints(root, state);
        List<ProjectFinding> findings = detectFindings(root, state, technologies, scripts);
        List<String> recommendations = buildRecommendations(findings, technologies, scripts, state);

        return new ProjectAnalysis(
                root.toString(),
                root.getFileName() != null ? root.getFileName().toString() : root.toString(),
                LocalDateTime.now().toString(),
                new ArrayList<>(technologies),
                scripts,
                entrypoints,
                state.toFileStats(),
                findings,
                recommendations);
    }

    private ScanState scan(Path root) {
        ScanState state = new ScanState(root);

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && shouldIgnoreDirectory(dir)) {
                        state.ignoredDirectories.add(relative(root, dir));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (state.totalFiles >= MAX_FILES_TO_SCAN) {
                        state.truncated = true;
                        return FileVisitResult.TERMINATE;
                    }

                    state.totalFiles++;
                    state.totalBytes += attrs.size();
                    String fileName = file.getFileName().toString();
                    state.fileNames.add(fileName);
                    state.relativeFiles.add(relative(root, file));

                    String extension = extensionOf(fileName);
                    if (!extension.isEmpty()) {
                        state.extensions.merge(extension, 1, Integer::sum);
                    }

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan project", e);
        }

        return state;
    }

    private Set<String> detectTechnologies(Path root, ScanState state) {
        Set<String> technologies = new LinkedHashSet<>();

        if (state.hasFile("pom.xml")) {
            technologies.add("Java");
            technologies.add("Maven");
            if (fileContains(root.resolve("pom.xml"), "spring-boot")) {
                technologies.add("Spring Boot");
            }
        }
        if (state.hasFile("build.gradle") || state.hasFile("build.gradle.kts")) {
            technologies.add("Gradle");
        }
        List<String> packageJsonFiles = state.relativeFiles.stream()
                .filter(file -> file.equals("package.json") || file.endsWith("/package.json"))
                .filter(file -> depthOf(file) <= 4)
                .toList();
        if (!packageJsonFiles.isEmpty()) {
            technologies.add("Node.js");
            for (String packageJsonFile : packageJsonFiles) {
                JsonNode packageJson = readJson(root.resolve(packageJsonFile));
                JsonNode dependencies = mergeDependencies(packageJson);
                if (dependencies.has("react")) technologies.add("React");
                if (dependencies.has("vite")) technologies.add("Vite");
                if (dependencies.has("next")) technologies.add("Next.js");
                if (dependencies.has("typescript")) technologies.add("TypeScript");
                if (dependencies.has("styled-components")) technologies.add("styled-components");
            }
        }
        if (state.hasFile("tsconfig.json")) technologies.add("TypeScript");
        if (state.hasFile("docker-compose.yml")
                || state.hasFile("docker-compose.yaml")
                || state.hasFile("Dockerfile")) {
            technologies.add("Docker");
        }
        if (state.hasFile("go.mod")) technologies.add("Go");
        if (state.hasFile("Cargo.toml")) technologies.add("Rust");
        if (state.hasFile("pyproject.toml") || state.hasFile("requirements.txt")) technologies.add("Python");
        if (state.hasFile("pubspec.yaml")) technologies.add("Flutter/Dart");

        return technologies;
    }

    private List<ProjectScript> detectScripts(Path root, ScanState state) {
        List<ProjectScript> scripts = new ArrayList<>();

        state.relativeFiles.stream()
                .filter(file -> file.equals("package.json") || file.endsWith("/package.json"))
                .filter(file -> depthOf(file) <= 4)
                .forEach(file -> {
                    Path packageJsonPath = root.resolve(file);
                    Path modulePath = packageJsonPath.getParent() == null ? root : packageJsonPath.getParent();
                    JsonNode packageJson = readJson(packageJsonPath);
                    JsonNode scriptNode = packageJson.path("scripts");
                    if (scriptNode.isObject()) {
                        scriptNode
                                .fields()
                                .forEachRemaining(entry -> scripts.add(new ProjectScript(
                                        "npm", entry.getKey(), entry.getValue().asText(), modulePath.toString())));
                    }
                });

        state.relativeFiles.stream()
                .filter(file -> file.equals("pom.xml") || file.endsWith("/pom.xml"))
                .filter(file -> depthOf(file) <= 4)
                .forEach(file -> {
                    Path pomPath = root.resolve(file);
                    Path modulePath = pomPath.getParent() == null ? root : pomPath.getParent();
                    scripts.add(new ProjectScript("maven", "test", "mvn test", modulePath.toString()));
                    scripts.add(new ProjectScript("maven", "package", "mvn package", modulePath.toString()));
                });

        if (Files.exists(root.resolve("docker-compose.yml")) || Files.exists(root.resolve("docker-compose.yaml"))) {
            scripts.add(new ProjectScript("docker", "up", "docker compose up", root.toString()));
        }

        return scripts;
    }

    private List<String> detectEntrypoints(Path root, ScanState state) {
        List<String> entrypoints = new ArrayList<>();
        addIfPresent(entrypoints, state, "pom.xml");
        addIfPresent(entrypoints, state, "package.json");
        addIfPresent(entrypoints, state, "docker-compose.yml");
        addIfPresent(entrypoints, state, "src/main/resources/application.properties");
        addIfPresent(entrypoints, state, "src/main/resources/application.yml");
        addIfPresent(entrypoints, state, "src/main/resources/application.yaml");
        addIfPresent(entrypoints, state, "src/main.tsx");
        addIfPresent(entrypoints, state, "src/App.tsx");
        addIfPresent(entrypoints, state, "index.html");

        state.relativeFiles.stream()
                .filter(file -> file.endsWith("Application.java") || file.endsWith("Application.kt"))
                .limit(5)
                .forEach(entrypoints::add);

        return entrypoints;
    }

    private List<ProjectFinding> detectFindings(
            Path root, ScanState state, Set<String> technologies, List<ProjectScript> scripts) {
        List<ProjectFinding> findings = new ArrayList<>();

        if (state.totalFiles == 0) {
            findings.add(new ProjectFinding(
                    "info", "Empty project directory", "The connected directory contains no files to analyze."));
            return findings;
        }

        if (!state.hasFile(".gitignore")) {
            findings.add(new ProjectFinding("warning", "Repository hygiene", "No .gitignore found at project root."));
        }
        if (state.fileNames.stream().anyMatch(name -> name.equals(".env") || name.startsWith(".env."))) {
            findings.add(
                    new ProjectFinding("high", "Secret risk", "Environment files are present in the project tree."));
        }
        if (state.relativeFiles.stream().anyMatch(file -> file.endsWith(".rej") || file.endsWith(".orig"))) {
            findings.add(new ProjectFinding(
                    "warning", "Patch leftovers", "Patch/reject backup files are present and should be reviewed."));
        }
        if (state.relativeFiles.stream().anyMatch(file -> file.endsWith(".mv.db") || file.endsWith(".trace.db"))) {
            findings.add(new ProjectFinding(
                    "warning", "Runtime artifacts", "Local database files are present in the workspace."));
        }
        if (state.ignoredDirectories.stream().anyMatch(dir -> dir.contains("node_modules"))) {
            findings.add(new ProjectFinding(
                    "info", "Large dependency folder", "node_modules is present and ignored during analysis."));
        }
        if (state.ignoredDirectories.stream().anyMatch(dir -> dir.contains(".venv"))) {
            findings.add(new ProjectFinding(
                    "info",
                    "Local virtualenv",
                    "A Python virtual environment is present and ignored during analysis."));
        }
        if (technologies.contains("Node.js")
                && scripts.stream().noneMatch(script -> script.name().equals("test"))) {
            findings.add(new ProjectFinding("warning", "Missing test script", "package.json has no npm test script."));
        }
        if (technologies.contains("TypeScript")
                && scripts.stream().noneMatch(script -> script.name().equals("typecheck"))) {
            findings.add(new ProjectFinding("info", "TypeScript validation", "No typecheck script was detected."));
        }
        if (!Files.exists(root.resolve("src/test"))) {
            findings.add(new ProjectFinding(
                    "info", "Test coverage", "No src/test directory was found at the project root."));
        }
        if (state.truncated) {
            findings.add(new ProjectFinding(
                    "warning",
                    "Scan truncated",
                    "The project scan reached the file limit of " + MAX_FILES_TO_SCAN + "."));
        }

        if (h2ConsoleEnabled(root)) {
            findings.add(new ProjectFinding(
                    "high", "H2 console", "H2 console appears to be enabled in application configuration."));
        }

        return findings;
    }

    private List<String> buildRecommendations(
            List<ProjectFinding> findings, Set<String> technologies, List<ProjectScript> scripts, ScanState state) {
        List<String> recommendations = new ArrayList<>();

        if (state.totalFiles == 0) {
            recommendations.add(
                    "Adicionar ou conectar os arquivos reais do projeto antes de avaliar stack, riscos ou credenciais.");
            return recommendations;
        }

        if (findings.stream().anyMatch(finding -> finding.severity().equals("high"))) {
            recommendations.add("Corrigir primeiro os achados de severidade alta antes de expandir funcionalidades.");
        }
        if (technologies.contains("Spring Boot")) {
            recommendations.add(
                    "Criar testes para controllers/services críticos e manter endpoints locais protegidos.");
        }
        if (technologies.contains("React")) {
            recommendations.add("Manter build com typecheck e dividir bundles grandes quando a UI crescer.");
        }
        if (scripts.stream().noneMatch(script -> script.name().equals("test"))) {
            recommendations.add("Adicionar comando de teste padrão para facilitar validação automática pelo agente.");
        }
        if (state.totalFiles > 1000) {
            recommendations.add("Usar análise incremental/watchers para evitar reindexação completa a cada mudança.");
        }
        if (recommendations.isEmpty()) {
            recommendations.add("Projeto parece pronto para uma análise semântica mais profunda via agente/RAG.");
        }

        return recommendations;
    }

    private JsonNode readJson(Path path) {
        if (!Files.exists(path)) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private JsonNode mergeDependencies(JsonNode packageJson) {
        Map<String, JsonNode> merged = new LinkedHashMap<>();
        mergeObjectFields(merged, packageJson.path("dependencies"));
        mergeObjectFields(merged, packageJson.path("devDependencies"));
        return mapper.valueToTree(merged);
    }

    private void mergeObjectFields(Map<String, JsonNode> target, JsonNode node) {
        if (!node.isObject()) {
            return;
        }
        node.fields().forEachRemaining(entry -> target.put(entry.getKey(), entry.getValue()));
    }

    private boolean fileContains(Path path, String value) {
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return false;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains(value);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean h2ConsoleEnabled(Path root) {
        if (fileContains(root.resolve("src/main/resources/application.properties"), "spring.h2.console.enabled=true")) {
            return true;
        }

        for (String fileName : List.of("application.yml", "application.yaml")) {
            Path path = root.resolve("src/main/resources").resolve(fileName);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                continue;
            }
            try {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                if (content.contains("h2:") && content.contains("console:") && content.contains("enabled: true")) {
                    return true;
                }
            } catch (IOException ignored) {
                return false;
            }
        }

        return false;
    }

    private void addIfPresent(List<String> entrypoints, ScanState state, String path) {
        if (state.relativeFiles.contains(path)) {
            entrypoints.add(path);
        }
    }

    private boolean shouldIgnoreDirectory(Path dir) {
        Path fileName = dir.getFileName();
        return fileName != null && IGNORED_DIRECTORIES.contains(fileName.toString());
    }

    private String relative(Path root, Path path) {
        return root.relativize(path).toString();
    }

    private String extensionOf(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index <= 0 || index == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(index + 1).toLowerCase();
    }

    private int depthOf(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return 0;
        }
        return relativePath.split("[/\\\\]").length - 1;
    }

    private static class ScanState {
        private final Path root;
        private final List<String> ignoredDirectories = new ArrayList<>();
        private final List<String> relativeFiles = new ArrayList<>();
        private final Set<String> fileNames = new LinkedHashSet<>();
        private final Map<String, Integer> extensions = new LinkedHashMap<>();
        private int totalFiles = 0;
        private long totalBytes = 0;
        private boolean truncated = false;

        private ScanState(Path root) {
            this.root = root;
        }

        private boolean hasFile(String fileName) {
            return Files.exists(root.resolve(fileName)) || fileNames.contains(fileName);
        }

        private FileStats toFileStats() {
            return new FileStats(totalFiles, totalBytes, extensions, ignoredDirectories, truncated);
        }
    }
}
