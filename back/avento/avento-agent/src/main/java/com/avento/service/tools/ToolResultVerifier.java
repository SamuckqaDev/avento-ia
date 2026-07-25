package com.avento.service.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Converts transport-level success into a verified Avento tool result. */
@Component
public class ToolResultVerifier {

    private final ObjectMapper mapper;

    public ToolResultVerifier(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode verify(String toolName, Map<String, Object> arguments, JsonNode result) {
        if (result == null || result.isNull() || result.isMissingNode()) {
            return error("A ferramenta não retornou resultado.");
        }
        if (result.has("error")) {
            return result;
        }
        if (result.path("isError").asBoolean(false)) {
            ObjectNode copy = result.isObject() ? ((ObjectNode) result).deepCopy() : mapper.createObjectNode();
            copy.put("error", "O servidor MCP informou falha na execução.");
            return copy;
        }

        if ("terminal_run".equals(toolName)) {
            JsonNode execution = result.path("execution");
            if (execution.path("timedOut").asBoolean(false)) {
                return withError(result, "O comando excedeu o tempo limite.");
            }
            if (execution.has("exitCode") && execution.path("exitCode").asInt(-1) != 0) {
                return withError(
                        result,
                        "O comando terminou com exit code "
                                + execution.path("exitCode").asInt() + ".");
            }
            Path scaffoldRoot = expectedNestScaffold(arguments);
            if (scaffoldRoot != null && !Files.isRegularFile(scaffoldRoot.resolve("package.json"))) {
                return withError(result, "O Nest CLI terminou sem criar o projeto esperado em " + scaffoldRoot + ".");
            }
        }

        String requestedPath = argument(arguments, "path");
        if (requiresExistingPath(toolName) && !requestedPath.isBlank() && !Files.exists(Path.of(requestedPath))) {
            return withError(result, "A ferramenta terminou, mas o caminho esperado não existe: " + requestedPath);
        }

        return result;
    }

    private boolean requiresExistingPath(String toolName) {
        return "write_file".equals(toolName)
                || "edit_file".equals(toolName)
                || "create_directory".equals(toolName)
                || "open_path".equals(toolName)
                || "reveal_in_finder".equals(toolName);
    }

    private String argument(Map<String, Object> arguments, String key) {
        if (arguments == null || arguments.get(key) == null) {
            return "";
        }
        return String.valueOf(arguments.get(key)).trim();
    }

    private Path expectedNestScaffold(Map<String, Object> arguments) {
        String command = argument(arguments, "command");
        String workingDirectory = argument(arguments, "path");
        if (command.isBlank() || workingDirectory.isBlank()) {
            return null;
        }
        List<String> tokens = List.of(command.trim().split("\\s+"));
        int newIndex = tokens.indexOf("new");
        boolean nestCommand = command.contains("@nestjs/cli") || command.matches("(^|.*\\s)nest\\s+new\\s+.*");
        if (!nestCommand || newIndex < 0 || newIndex + 1 >= tokens.size()) {
            return null;
        }
        String projectName = tokens.get(newIndex + 1);
        if (projectName.isBlank() || projectName.startsWith("-")) {
            return null;
        }
        Path root = Path.of(workingDirectory).toAbsolutePath().normalize();
        return ".".equals(projectName) ? root : root.resolve(projectName).normalize();
    }

    private JsonNode withError(JsonNode result, String message) {
        ObjectNode copy = result.isObject() ? ((ObjectNode) result).deepCopy() : mapper.createObjectNode();
        copy.put("error", message);
        return copy;
    }

    private ObjectNode error(String message) {
        return mapper.createObjectNode().put("error", message);
    }
}
