package com.avento.service.intent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IntentEmbeddingClassifierTest {

    @Test
    void returnsEmptyWhenEmbeddingModelIsUnavailable() {
        IntentEmbeddingClassifier classifier = new IntentEmbeddingClassifier(null, 0.55, 2000);

        assertTrue(classifier.classify("Desenha uma paisagem ao por do sol").isEmpty());
    }

    @Test
    void returnsEmptyForBlankMessage() {
        IntentEmbeddingClassifier classifier = new IntentEmbeddingClassifier(null, 0.55, 2000);

        assertTrue(classifier.classify("   ").isEmpty());
        assertTrue(classifier.classify(null).isEmpty());
    }
}
