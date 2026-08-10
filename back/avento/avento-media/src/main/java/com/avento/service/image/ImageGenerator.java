package com.avento.service.image;

import java.util.Map;
import tools.jackson.databind.node.ObjectNode;

public interface ImageGenerator {
    ObjectNode generate(Map<String, Object> payload);

    String resolveModel(Map<String, Object> payload);

    void cancel(Thread worker, String model);
}
