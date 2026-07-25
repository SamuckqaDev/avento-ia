package com.avento.service.execution;

import java.util.UUID;

public interface RunEventPublisher {

    void publish(String runId, UUID userId, Long chatId, String rawEvent);
}
