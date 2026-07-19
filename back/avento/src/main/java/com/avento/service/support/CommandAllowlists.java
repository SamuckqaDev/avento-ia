package com.avento.service.support;

import java.util.Set;

/**
 * Single source of truth for command allowlist values shared between the agent's terminal tools
 * (McpController) and the no-approval validation runner (ProjectCommandService), so the two never
 * drift apart. npm scope is intentionally NOT shared here: McpController allows any npm/npx
 * command behind a user approval step, while ProjectCommandService runs directly from the UI with
 * no approval step and must stay limited to a fixed script whitelist.
 */
public final class CommandAllowlists {

    public static final Set<String> MAVEN_GOALS = Set.of("test", "package", "verify");

    private CommandAllowlists() {}
}
