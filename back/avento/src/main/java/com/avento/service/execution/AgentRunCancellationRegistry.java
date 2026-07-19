package com.avento.service.execution;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

@Component
public class AgentRunCancellationRegistry {

    private final Map<String, Disposable> activeRuns = new ConcurrentHashMap<>();

    public void register(String runId, Disposable disposable) {
        Disposable previous = activeRuns.put(runId, disposable);
        if (previous != null && !previous.isDisposed()) {
            previous.dispose();
        }
    }

    public void unregister(String runId, Disposable disposable) {
        activeRuns.remove(runId, disposable);
    }

    public boolean cancel(String runId) {
        Disposable disposable = activeRuns.remove(runId);
        if (disposable == null) {
            return false;
        }
        disposable.dispose();
        return true;
    }
}
