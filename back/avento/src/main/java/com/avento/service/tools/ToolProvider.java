package com.avento.service.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.Map;

/** Provider contract for the agent tool catalog and execution transport. */
public interface ToolProvider {

    ArrayNode listTools();

    JsonNode execute(String toolName, Map<String, Object> arguments) throws Exception;
}
