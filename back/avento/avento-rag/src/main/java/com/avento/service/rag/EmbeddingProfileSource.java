package com.avento.service.rag;

import java.util.Optional;

/**
 * Where the RAG module learns which embedding model to use.
 *
 * <p>The choice lives in the user's provider settings, which belong to the agent module — and the
 * agent module already depends on this one, so it cannot be imported back. The interface inverts
 * that: the agent implements it, the RAG consumes it, and neither module has to know the other's
 * types. With no implementation on the classpath the RAG keeps the model wired in the YAML, which
 * is what happens in tests.
 */
public interface EmbeddingProfileSource {

    Optional<EmbeddingProfile> active();
}
