package com.avento.service.image;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

public interface ImageGenerator {
    ObjectNode generate(Map<String, Object> payload);

    String resolveModel(Map<String, Object> payload);

    void cancel(Thread worker, String model);
}
