package com.avento.service.rag;

import java.util.Locale;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * An embedding model chosen in the settings, already built, plus the name it is known by.
 *
 * <p>The model arrives constructed rather than as an address and a name on purpose: building it
 * means knowing the provider — today Ollama, tomorrow whatever else — and this module has no
 * business knowing that. It only needs something that turns text into vectors and a stable name to
 * hang an index on.
 *
 * @param name model name as configured, e.g. {@code nomic-embed-text} or {@code bge-m3}
 * @param model the embedding model itself
 */
public record EmbeddingProfile(String name, EmbeddingModel model) {

    /**
     * Index suffix for this profile.
     *
     * <p>The index name carries the model because embeddings of different models are not comparable
     * and usually do not even have the same width — {@code nomic-embed-text} emits 768 numbers and
     * {@code bge-m3} emits 1024. Writing both into one Redis index either fails on the declared
     * dimension or, worse, silently returns nonsense distances. A separate index per model makes
     * switching safe: the new one starts empty and the indexer fills it, and the old vectors stay
     * put in case the choice is reverted.
     */
    public String indexSuffix() {
        String raw = (name == null ? "" : name).toLowerCase(Locale.ROOT);
        String slug = raw.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? "default" : slug;
    }

    public boolean isUsable() {
        return name != null && !name.isBlank() && model != null;
    }
}
