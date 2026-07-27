package com.avento.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectPathsTest {

    private final String originalWorkingDirectory = System.getProperty("user.dir");
    private final String originalProjectRoot = System.getProperty("avento.project.root");

    @AfterEach
    void restoreSystemProperties() {
        System.setProperty("user.dir", originalWorkingDirectory);
        if (originalProjectRoot == null) {
            System.clearProperty("avento.project.root");
        } else {
            System.setProperty("avento.project.root", originalProjectRoot);
        }
    }

    @Test
    void findsProjectRootWhenBackendChangesTheWorkingDirectory(@TempDir Path tempDir) throws IOException {
        Path backendDirectory = Files.createDirectories(tempDir.resolve("back").resolve("avento"));
        Files.createDirectories(tempDir.resolve("scripts"));
        System.clearProperty("avento.project.root");
        System.setProperty("user.dir", backendDirectory.toString());

        assertEquals(tempDir, ProjectPaths.projectRoot());
        assertEquals(tempDir.resolve("piper_tts/.venv/bin/piper"), ProjectPaths.resolve("./piper_tts/.venv/bin/piper"));
    }

    @Test
    void configuredProjectRootTakesPriority(@TempDir Path tempDir) {
        System.setProperty("avento.project.root", tempDir.toString());

        assertEquals(tempDir.resolve("voice/model.onnx"), ProjectPaths.resolve("voice/model.onnx"));
    }

    @Test
    void keepsAbsolutePathsUnchanged(@TempDir Path tempDir) {
        Path binary = tempDir.resolve("piper");

        assertEquals(binary, ProjectPaths.resolve(binary.toString()));
    }
}
