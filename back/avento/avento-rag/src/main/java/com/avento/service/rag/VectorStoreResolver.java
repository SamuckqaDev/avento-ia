package com.avento.service.rag;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPooled;

/**
 * Hands out the vector store that matches the embedding model currently chosen in the settings.
 *
 * <p>Before this, the store came straight from Spring's auto-configuration, pinned at boot to
 * {@code spring.ai.ollama.embedding.model} on {@code spring.ai.ollama.base-url}. Choosing another
 * embedding model in the interface changed a row in the database and nothing else: the setting was
 * saved, displayed, and ignored. Worse than doing nothing, because it read as configured.
 *
 * <p>Each distinct model gets its own Redis index (see {@link EmbeddingProfile#indexSuffix()}), so
 * switching cannot mix vectors of different widths. Stores are cached per profile — building one is
 * cheap but it opens a schema, and the search path runs on every tool call.
 */
@Component
public class VectorStoreResolver {

    private static final Logger logger = LoggerFactory.getLogger(VectorStoreResolver.class);

    private final VectorStore autoConfiguredStore;
    private final ObjectProvider<JedisPooled> jedisProvider;
    private final ObjectProvider<EmbeddingProfileSource> profileSourceProvider;
    private final Map<String, VectorStore> storesByProfile = new ConcurrentHashMap<>();

    private final String baseIndexName;
    private final String prefix;

    public VectorStoreResolver(
            VectorStore autoConfiguredStore,
            ObjectProvider<JedisPooled> jedisProvider,
            ObjectProvider<EmbeddingProfileSource> profileSourceProvider,
            @Value("${spring.ai.vectorstore.redis.index:avento_index}") String baseIndexName,
            @Value("${spring.ai.vectorstore.redis.prefix:avento:}") String prefix) {
        this.autoConfiguredStore = autoConfiguredStore;
        this.jedisProvider = jedisProvider;
        this.profileSourceProvider = profileSourceProvider;
        this.baseIndexName = baseIndexName;
        this.prefix = prefix;
    }

    /** The store to read and write, for whatever embedding model is configured right now. */
    public VectorStore active() {
        Optional<EmbeddingProfile> profile = activeProfile();
        if (profile.isEmpty()) {
            return autoConfiguredStore;
        }
        EmbeddingProfile resolved = profile.get();
        return storesByProfile.computeIfAbsent(cacheKey(resolved), ignored -> build(resolved));
    }

    /** The embedding model itself, for callers that embed without a store (intent classification). */
    public Optional<EmbeddingModel> activeEmbeddingModel() {
        return activeProfile().map(EmbeddingProfile::model);
    }

    /** Name of the index in use, for diagnostics and for the docs to be checkable. */
    public String activeIndexName() {
        return activeProfile()
                .map(profile -> baseIndexName + "_" + profile.indexSuffix())
                .orElse(baseIndexName);
    }

    private Optional<EmbeddingProfile> activeProfile() {
        EmbeddingProfileSource source = profileSourceProvider.getIfAvailable();
        if (source == null) {
            return Optional.empty();
        }
        try {
            return source.active().filter(EmbeddingProfile::isUsable);
        } catch (Exception exception) {
            // A settings read must never take the search down: falling back to the auto-configured
            // store still answers, it just answers with the model from the YAML.
            logger.warn("Could not read the configured embedding model; using the one from the YAML", exception);
            return Optional.empty();
        }
    }

    private VectorStore build(EmbeddingProfile profile) {
        JedisPooled jedis = jedisProvider.getIfAvailable();
        if (jedis == null) {
            logger.warn("No Redis client available; keeping the auto-configured vector store");
            return autoConfiguredStore;
        }
        String indexName = baseIndexName + "_" + profile.indexSuffix();
        logger.info("Vector index {} using embedding model {}", indexName, profile.name());
        return RedisVectorStore.builder(jedis, profile.model())
                .indexName(indexName)
                .prefix(prefix)
                .initializeSchema(true)
                .build();
    }

    private String cacheKey(EmbeddingProfile profile) {
        return profile.indexSuffix();
    }
}
