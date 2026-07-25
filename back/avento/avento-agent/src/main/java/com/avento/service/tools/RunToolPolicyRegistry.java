package com.avento.service.tools;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Per-run tool allow-list, keyed by run id. When a plan task runs with a specialized agent, the
 * agent's {@code allowedTools} are registered here so the agent loop can restrict the exposed
 * toolset to that agent's scope — without threading a new parameter through the streaming interface.
 *
 * <p>An absent or empty entry means "no restriction" (all eligible tools stay available).
 */
@Service
public class RunToolPolicyRegistry {

    private final ConcurrentHashMap<String, Set<String>> allowedToolsByRun = new ConcurrentHashMap<>();

    public void allow(String runId, Set<String> toolNames) {
        if (runId == null || runId.isBlank() || toolNames == null || toolNames.isEmpty()) {
            return;
        }
        allowedToolsByRun.put(runId, Set.copyOf(toolNames));
    }

    /** Returns the allow-list for the run, or an empty set when the run has no restriction. */
    public Set<String> allowed(String runId) {
        if (runId == null) {
            return Set.of();
        }
        return allowedToolsByRun.getOrDefault(runId, Set.of());
    }

    public void clear(String runId) {
        if (runId != null) {
            allowedToolsByRun.remove(runId);
        }
    }
}
