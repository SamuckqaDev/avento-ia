package com.avento.service;

import com.avento.service.dto.*;
import com.avento.service.support.CommandAllowlists;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class ProjectCommandService {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final int MAX_OUTPUT_CHARS = 20000;
    private static final Set<String> ALLOWED_NPM_SCRIPTS = Set.of("test", "build", "typecheck", "lint", "validate");

    private final WorkspaceAccessService workspaceAccessService;

    public ProjectCommandService(WorkspaceAccessService workspaceAccessService) {
        this.workspaceAccessService = workspaceAccessService;
    }

    public ProjectCommandResult run(ProjectCommandRequest request) {
        return run(null, request);
    }

    public ProjectCommandResult run(UUID userId, ProjectCommandRequest request) {
        Path workingDirectory = userId == null
                ? workspaceAccessService.requireAuthorized(request.path())
                : workspaceAccessService.requireAuthorized(userId, request.path());
        if (!Files.isDirectory(workingDirectory)) {
            throw new IllegalArgumentException("Command path must be a directory");
        }

        List<String> command = buildCommand(request.runner(), request.name());
        long startedAt = System.currentTimeMillis();

        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(workingDirectory.toFile());
            processBuilder.redirectErrorStream(true);
            process = processBuilder.start();
            Process runningProcess = process;

            ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
            Thread outputReader = new Thread(() -> {
                try {
                    runningProcess.getInputStream().transferTo(outputBuffer);
                } catch (IOException ignored) {
                    // Process output is best-effort; exit code still tells us command status.
                }
            });
            outputReader.start();

            boolean completed = process.waitFor(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                outputReader.join(1000);
                return new ProjectCommandResult(
                        request.runner(),
                        request.name(),
                        String.join(" ", command),
                        -1,
                        true,
                        elapsedSeconds(startedAt),
                        truncate(outputBuffer.toString(StandardCharsets.UTF_8)),
                        LocalDateTime.now().toString());
            }

            outputReader.join(1000);
            return new ProjectCommandResult(
                    request.runner(),
                    request.name(),
                    String.join(" ", command),
                    process.exitValue(),
                    false,
                    elapsedSeconds(startedAt),
                    truncate(outputBuffer.toString(StandardCharsets.UTF_8)),
                    LocalDateTime.now().toString());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start command", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IllegalStateException("Command execution was interrupted", e);
        }
    }

    private List<String> buildCommand(String runner, String name) {
        if (runner == null || name == null) {
            throw new IllegalArgumentException("Runner and command name are required");
        }

        return switch (runner) {
            case "npm" -> {
                if (!ALLOWED_NPM_SCRIPTS.contains(name)) {
                    throw new SecurityException("NPM script is not allowed: " + name);
                }
                yield List.of("npm", "run", name);
            }
            case "maven" -> {
                if (!CommandAllowlists.MAVEN_GOALS.contains(name)) {
                    throw new SecurityException("Maven goal is not allowed: " + name);
                }
                yield List.of("mvn", name);
            }
            default -> throw new SecurityException("Runner is not allowed: " + runner);
        };
    }

    private double elapsedSeconds(long startedAt) {
        return Math.round(((System.currentTimeMillis() - startedAt) / 1000.0) * 10.0) / 10.0;
    }

    private String truncate(String output) {
        if (output == null || output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(output.length() - MAX_OUTPUT_CHARS);
    }
}
