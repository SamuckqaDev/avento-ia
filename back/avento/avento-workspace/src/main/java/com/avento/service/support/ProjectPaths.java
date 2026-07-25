package com.avento.service.support;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ProjectPaths {

    private static final String PROJECT_ROOT_ENV = "AVENTO_PROJECT_ROOT";
    private static final String PROJECT_ROOT_PROPERTY = "avento.project.root";

    private ProjectPaths() {}

    public static Path projectRoot() {
        String configuredRoot = System.getProperty(PROJECT_ROOT_PROPERTY);
        if (configuredRoot == null || configuredRoot.isBlank()) {
            configuredRoot = System.getenv(PROJECT_ROOT_ENV);
        }
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            return Path.of(configuredRoot.trim()).toAbsolutePath().normalize();
        }

        Path workingDirectory =
                Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path candidate = workingDirectory;
        while (candidate != null) {
            if (isProjectRoot(candidate)) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return workingDirectory;
    }

    public static Path resolve(String configuredPath, String... defaultPathParts) {
        Path path = configuredPath == null || configuredPath.isBlank()
                ? pathFromParts(defaultPathParts)
                : Path.of(configuredPath.trim());
        return path.isAbsolute()
                ? path.normalize()
                : projectRoot().resolve(path).normalize();
    }

    private static Path pathFromParts(String... pathParts) {
        Path path = Path.of("");
        for (String pathPart : pathParts) {
            path = path.resolve(pathPart);
        }
        return path;
    }

    private static boolean isProjectRoot(Path path) {
        return Files.isDirectory(path.resolve("back").resolve("avento")) && Files.isDirectory(path.resolve("scripts"));
    }
}
