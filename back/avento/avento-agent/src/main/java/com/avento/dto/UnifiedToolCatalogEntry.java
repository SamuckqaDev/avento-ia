package com.avento.dto;

/**
 * Uma capacidade exibida no catálogo operacional do Avento.
 *
 * <p>O catálogo diferencia a ferramenta executável de um servidor ainda desconectado. Assim a
 * interface não promete que o modelo pode chamar uma ferramenta que ainda não foi descoberta pelo
 * protocolo MCP.
 */
public record UnifiedToolCatalogEntry(
        String id,
        String entryType,
        String name,
        String source,
        String serverId,
        String description,
        String category,
        String riskLevel,
        String availability,
        boolean requiresConnection,
        String reason) {

    public static final String ENTRY_TYPE_TOOL = "TOOL";
    public static final String ENTRY_TYPE_SERVER = "SERVER";

    public static final String SOURCE_AVENTO_NATIVE = "AVENTO_NATIVE";
    public static final String SOURCE_LOCAL_MCP = "LOCAL_MCP";
    public static final String SOURCE_DOCKER_MCP = "DOCKER_MCP";

    public static final String AVAILABILITY_READY = "READY";
    public static final String AVAILABILITY_AVAILABLE = "AVAILABLE";
    public static final String AVAILABILITY_DEGRADED = "DEGRADED";
    public static final String AVAILABILITY_UNAVAILABLE = "UNAVAILABLE";
}
