package com.avento.service.intent;

import java.util.EnumSet;
import java.util.Set;

public record IntentProfile(Set<AgentIntent> intents) {

    public IntentProfile {
        intents = intents.isEmpty() ? Set.of() : Set.copyOf(intents);
    }

    public static IntentProfile of(EnumSet<AgentIntent> intents) {
        return new IntentProfile(intents);
    }

    public boolean has(AgentIntent intent) {
        return intents.contains(intent);
    }
}
