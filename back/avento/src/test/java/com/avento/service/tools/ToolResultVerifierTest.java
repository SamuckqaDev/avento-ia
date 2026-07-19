package com.avento.service.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolResultVerifierTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolResultVerifier verifier = new ToolResultVerifier(mapper);

    @TempDir
    Path tempDir;

    @Test
    void rejectsMissingToolResults() {
        assertTrue(verifier.verify("read_file", Map.of(), null).has("error"));
    }

    @Test
    void rejectsNonZeroTerminalExitCodes() {
        ObjectNode result = mapper.createObjectNode();
        result.putObject("execution").put("exitCode", 1).put("timedOut", false);

        JsonNode verified = verifier.verify("terminal_run", Map.of(), result);

        assertEquals(
                "O comando terminou com exit code 1.", verified.path("error").asText());
    }

    @Test
    void rejectsSuccessfulNestCommandWhenScaffoldWasNotCreated() {
        ObjectNode result = mapper.createObjectNode();
        result.putObject("execution").put("exitCode", 0).put("timedOut", false);

        JsonNode verified = verifier.verify(
                "terminal_run",
                Map.of("path", tempDir.toString(), "command", "npx --yes @nestjs/cli@latest new back"),
                result);

        assertTrue(verified.path("error").asText().contains("back"));
    }

    @Test
    void acceptsNestScaffoldOnlyAfterPackageJsonExists() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("back"));
        Files.writeString(project.resolve("package.json"), "{}");
        ObjectNode result = mapper.createObjectNode();
        result.putObject("execution").put("exitCode", 0).put("timedOut", false);

        JsonNode verified = verifier.verify(
                "terminal_run",
                Map.of("path", tempDir.toString(), "command", "npx --yes @nestjs/cli@latest new back"),
                result);

        assertFalse(verified.has("error"));
    }

    @Test
    void verifiesExpectedFilePostcondition() throws Exception {
        Path file = Files.writeString(tempDir.resolve("App.tsx"), "export {};\n");

        JsonNode verified = verifier.verify(
                "write_file",
                Map.of("path", file.toString()),
                mapper.createObjectNode().put("status", "written"));

        assertFalse(verified.has("error"));
    }
}
