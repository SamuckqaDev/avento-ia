package com.avento.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.service.dto.ProjectAnalysis;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectAnalysisServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsNodeReactViteProject() throws Exception {
        WorkspaceAccessService workspaceAccessService = new WorkspaceAccessService();
        ProjectAnalysisService service = new ProjectAnalysisService(workspaceAccessService, new ObjectMapper());
        Path project = Files.createDirectory(tempDir.resolve("app"));
        Files.createDirectory(project.resolve("src"));
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("src").resolve("main.tsx"), "import React from 'react';");
        Files.writeString(
                project.resolve("src/main/resources/application.yml"),
                "spring:\n  h2:\n    console:\n      enabled: false\n");
        Files.writeString(project.resolve("tsconfig.json"), "{}");
        Files.writeString(project.resolve("package.json"), """
                {
                  "scripts": {
                    "build": "vite build",
                    "typecheck": "tsc --noEmit"
                  },
                  "dependencies": {
                    "react": "19.0.0",
                    "vite": "8.0.0",
                    "typescript": "6.0.0"
                  }
                }
                """);

        workspaceAccessService.registerWorkspaceRoot(project.toString());

        ProjectAnalysis analysis = service.analyze(project.toString());

        assertEquals("app", analysis.projectName());
        assertTrue(analysis.technologies().contains("Node.js"));
        assertTrue(analysis.technologies().contains("React"));
        assertTrue(analysis.technologies().contains("Vite"));
        assertTrue(analysis.technologies().contains("TypeScript"));
        assertTrue(analysis.entrypoints().contains("src/main.tsx"));
        assertTrue(analysis.entrypoints().contains("src/main/resources/application.yml"));
        assertTrue(analysis.scripts().stream().anyMatch(script -> script.name().equals("build")));
        assertTrue(
                analysis.findings().stream().anyMatch(finding -> finding.title().equals("Missing test script")));
    }

    @Test
    void detectsNestedNodeProjectScriptPath() throws Exception {
        WorkspaceAccessService workspaceAccessService = new WorkspaceAccessService();
        ProjectAnalysisService service = new ProjectAnalysisService(workspaceAccessService, new ObjectMapper());
        Path project = Files.createDirectory(tempDir.resolve("repo"));
        Path web = Files.createDirectory(project.resolve("web"));
        Files.writeString(project.resolve("pom.xml"), "<project><artifactId>repo</artifactId></project>");
        Files.writeString(web.resolve("package.json"), """
                {
                  "scripts": {
                    "build": "vite build"
                  },
                  "dependencies": {
                    "react": "19.0.0",
                    "vite": "8.0.0"
                  }
                }
                """);

        workspaceAccessService.registerWorkspaceRoot(project.toString());

        ProjectAnalysis analysis = service.analyze(project.toString());
        String webPath = web.toRealPath().toString();

        assertTrue(analysis.technologies().contains("React"));
        assertTrue(analysis.technologies().contains("Vite"));
        assertTrue(analysis.scripts().stream()
                .anyMatch(script -> script.runner().equals("npm")
                        && script.name().equals("build")
                        && script.path().equals(webPath)));
    }

    @Test
    void ignoresLocalRuntimeDirectoriesWhenScanningProjectRoot() throws Exception {
        WorkspaceAccessService workspaceAccessService = new WorkspaceAccessService();
        ProjectAnalysisService service = new ProjectAnalysisService(workspaceAccessService, new ObjectMapper());
        Path project = Files.createDirectory(tempDir.resolve("repo"));
        Path whisper = Files.createDirectories(project.resolve("whisper.cpp"));
        Path piper = Files.createDirectories(project.resolve("piper_tts"));

        Files.writeString(project.resolve("pom.xml"), "<project><artifactId>repo</artifactId></project>");
        Files.writeString(whisper.resolve("go.mod"), "module ignored");
        Files.writeString(piper.resolve("requirements.txt"), "ignored");

        workspaceAccessService.registerWorkspaceRoot(project.toString());

        ProjectAnalysis analysis = service.analyze(project.toString());

        assertTrue(analysis.technologies().contains("Java"));
        assertTrue(analysis.technologies().contains("Maven"));
        assertTrue(analysis.technologies().stream().noneMatch(technology -> technology.equals("Go")));
        assertTrue(analysis.technologies().stream().noneMatch(technology -> technology.equals("Python")));
    }

    @Test
    void rejectsUnauthorizedProjectPath() throws Exception {
        WorkspaceAccessService workspaceAccessService = new WorkspaceAccessService();
        ProjectAnalysisService service = new ProjectAnalysisService(workspaceAccessService, new ObjectMapper());
        Path project = Files.createDirectory(tempDir.resolve("app"));

        assertThrows(SecurityException.class, () -> service.analyze(project.toString()));
    }

    @Test
    void analyzesWorkspaceAuthorizedForAuthenticatedUserOnly() throws Exception {
        WorkspaceAccessService workspaceAccessService = new WorkspaceAccessService();
        ProjectAnalysisService service = new ProjectAnalysisService(workspaceAccessService, new ObjectMapper());
        UUID owner = UUID.randomUUID();
        Path project = Files.createDirectory(tempDir.resolve("owned-app"));
        Files.writeString(project.resolve("package.json"), "{}");
        workspaceAccessService.registerWorkspaceRoot(owner, project.toString());

        assertEquals("owned-app", service.analyze(owner, project.toString()).projectName());
        assertThrows(SecurityException.class, () -> service.analyze(UUID.randomUUID(), project.toString()));
    }
}
