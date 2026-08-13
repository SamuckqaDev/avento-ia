package com.avento.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class LocalToolSchemaCharacterizationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void keepsTheFortyThreeLocalToolSchemasSemanticallyIdentical() throws Exception {
        ArrayNode actual = localToolSnapshot();
        ArrayNode expected = baseline();

        assertThat(actual).hasSize(43);
        assertThat(expected).hasSize(43);

        for (int index = 0; index < expected.size(); index++) {
            JsonNode expectedTool = expected.get(index);
            JsonNode actualTool = actual.get(index);

            assertThat(actualTool.path("name").asText()).isEqualTo(expectedTool.path("name").asText());
            assertThat(actualTool.path("description").asText())
                    .isEqualTo(expectedTool.path("description").asText());
            assertThat(mapper.readTree(actualTool.path("inputSchema").toString()))
                    .isEqualTo(mapper.readTree(expectedTool.path("inputSchema").toString()));
        }
    }

    private ArrayNode baseline() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/tool-schemas-baseline.json")) {
            assertThat(input).as("tool schema baseline").isNotNull();
            return (ArrayNode) mapper.readTree(input);
        }
    }

    private ArrayNode localToolSnapshot() throws Exception {
        McpController controller = new McpController();
        Method method = McpController.class.getDeclaredMethod("getAvailableToolsInternal");
        method.setAccessible(true);
        ArrayNode tools = (ArrayNode) method.invoke(controller);
        ArrayNode snapshot = mapper.createArrayNode();

        for (JsonNode tool : tools) {
            ObjectNode entry = mapper.createObjectNode();
            entry.put("name", tool.path("name").asText());
            entry.put("description", tool.path("description").asText());
            entry.set("inputSchema", tool.path("inputSchema").deepCopy());
            snapshot.add(entry);
        }

        return snapshot;
    }
}
