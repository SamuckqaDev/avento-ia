package com.avento.service.intent;

import com.avento.service.support.HeuristicWordLists;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Classifica a intencao de uma mensagem por similaridade de significado
// (embedding) em vez de palavra-chave exata, usando o mesmo modelo de
// embedding ja usado pelo RAG (nomic-embed-text via Ollama). Funciona como
// sinal adicional ao lado do IntentRouter baseado em palavra-chave: se o
// Ollama estiver indisponivel ou lento, retorna vazio e quem chamou cai de
// volta para o classificador por palavra-chave.
@Component
public class IntentEmbeddingClassifier {

    private static final Logger logger = LoggerFactory.getLogger(IntentEmbeddingClassifier.class);
    private static final String EXAMPLES_RESOURCE = "agent/heuristics/intent-examples.txt";

    private final EmbeddingModel embeddingModel;
    private final double similarityThreshold;
    private final long timeoutMillis;

    private volatile Map<AgentIntent, List<float[]>> categoryExampleEmbeddings;
    private volatile boolean embeddingUnavailable = false;

    public IntentEmbeddingClassifier(
            EmbeddingModel embeddingModel,
            @Value("${avento.agent.intent-embedding-threshold:0.72}") double similarityThreshold,
            @Value("${avento.agent.intent-embedding-timeout-ms:2000}") long timeoutMillis) {
        this.embeddingModel = embeddingModel;
        this.similarityThreshold = similarityThreshold;
        this.timeoutMillis = timeoutMillis;
    }

    public Optional<EnumSet<AgentIntent>> classify(String message) {
        if (embeddingModel == null || embeddingUnavailable || message == null || message.isBlank()) {
            return Optional.empty();
        }

        try {
            Map<AgentIntent, List<float[]>> examples = ensureExampleEmbeddings();
            float[] messageEmbedding = embedWithTimeout(message);

            EnumSet<AgentIntent> matched = EnumSet.noneOf(AgentIntent.class);
            for (Map.Entry<AgentIntent, List<float[]>> entry : examples.entrySet()) {
                double bestSimilarity = 0;
                for (float[] exampleEmbedding : entry.getValue()) {
                    bestSimilarity = Math.max(bestSimilarity, cosineSimilarity(messageEmbedding, exampleEmbedding));
                }
                if (bestSimilarity >= similarityThreshold) {
                    matched.add(entry.getKey());
                }
            }
            return Optional.of(matched);
        } catch (TimeoutException exception) {
            logger.warn("Intent embedding timed out after {}ms, falling back to keyword matching", timeoutMillis);
            return Optional.empty();
        } catch (Exception exception) {
            logger.warn("Intent embedding classification failed, falling back to keyword matching", exception);
            embeddingUnavailable = true;
            return Optional.empty();
        }
    }

    private Map<AgentIntent, List<float[]>> ensureExampleEmbeddings() throws TimeoutException {
        Map<AgentIntent, List<float[]>> cached = categoryExampleEmbeddings;
        if (cached != null) {
            return cached;
        }

        synchronized (this) {
            if (categoryExampleEmbeddings != null) {
                return categoryExampleEmbeddings;
            }

            Map<String, List<String>> sections = HeuristicWordLists.loadSections(EXAMPLES_RESOURCE);
            Map<AgentIntent, List<float[]>> embedded = new EnumMap<>(AgentIntent.class);
            for (Map.Entry<String, List<String>> section : sections.entrySet()) {
                AgentIntent intent = AgentIntent.valueOf(section.getKey());
                List<float[]> vectors = new ArrayList<>();
                for (String example : section.getValue()) {
                    vectors.add(embedWithTimeout(example));
                }
                embedded.put(intent, vectors);
            }
            categoryExampleEmbeddings = Map.copyOf(embedded);
            return categoryExampleEmbeddings;
        }
    }

    private float[] embedWithTimeout(String text) throws TimeoutException {
        CompletableFuture<float[]> future = CompletableFuture.supplyAsync(() -> embeddingModel.embed(text));
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for embedding", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Embedding call failed", exception.getCause());
        }
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
