package com.avento.service.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.avento.dto.ToolDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DockerMcpToolPrecedenceTest {

    @Test
    void replacesOnlyAnExactNativeNameAnnouncedByTheDockerGateway() {
        ToolDefinition dockerReadFile = tool("docker-gateway__read_file", "read_file", "docker-gateway");
        ToolDefinition localReadFile = tool("filesystem__read_file", "read_file", "filesystem");
        ToolDefinition similarlyNamedTool = tool("docker-gateway__read_files", "read_files", "docker-gateway");

        Map<String, ToolDefinition> replacements =
                DockerMcpToolPrecedence.nativeReplacements(List.of(localReadFile, similarlyNamedTool, dockerReadFile));

        assertThat(replacements).containsOnlyKeys("read_file");
        assertThat(replacements.get("read_file")).isEqualTo(dockerReadFile);
        assertThat(DockerMcpToolPrecedence.canonicalName(dockerReadFile)).contains("read_file");
        assertThat(DockerMcpToolPrecedence.canonicalName(localReadFile)).isEmpty();
    }

    @Test
    void prioritizesTheSelectedDockerReplacementBeforeOtherCollidingTools() {
        ToolDefinition dockerReadFile = tool("docker-gateway__read_file", "read_file", "docker-gateway");
        ToolDefinition localReadFile = tool("filesystem__read_file", "read_file", "filesystem");

        assertThat(DockerMcpToolPrecedence.prioritize(List.of(localReadFile, dockerReadFile)))
                .startsWith(dockerReadFile);
    }

    private ToolDefinition tool(String exposedName, String originalName, String serverName) {
        return new ToolDefinition(exposedName, originalName, serverName, "Lê um arquivo.", Map.of());
    }
}
