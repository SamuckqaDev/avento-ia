package com.avento.service.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Rewrites image-generation prompts into concise English before they reach the SDXL pipeline.
 * CLIP only understands English and truncates at 77 tokens, so Portuguese prompts arrive as
 * noise for the checkpoint. Any failure falls back to the original prompt.
 */
@Service
public class ImagePromptTranslator {

    private static final Logger logger = LoggerFactory.getLogger(ImagePromptTranslator.class);
    private static final String INSTRUCTION =
            "You translate image generation requests into English for a Stable Diffusion model. "
                    + "Translate the request below to English, preserving every requested detail: subjects, "
                    + "how many of them, colors, style, composition, setting and camera hints. Translate only. "
                    + "Never invent details, objects or scenery that the user did not ask for. Do not explain, "
                    + "do not use quotes. If the request is already in English, return it unchanged. "
                    + "Output only the translated prompt.";

    private final ObjectMapper mapper;
    private final String ollamaBaseUrl;
    private final boolean translationEnabled;
    private final String translationModel;
    private final long translationTimeoutSeconds;
    private final String keepAlive;

    public ImagePromptTranslator(
            ObjectMapper mapper,
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
            @Value("${avento.image.translation-enabled:true}") boolean translationEnabled,
            @Value("${avento.image.translation-model:${avento.agent.default-model:qwen3:8b}}") String translationModel,
            @Value("${avento.image.translation-timeout-seconds:45}") long translationTimeoutSeconds,
            @Value("${avento.agent.keep-alive:30m}") String keepAlive) {
        this.mapper = mapper;
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.translationEnabled = translationEnabled;
        this.translationModel = translationModel;
        this.translationTimeoutSeconds = translationTimeoutSeconds;
        this.keepAlive = keepAlive;
    }

    public String toEnglish(String prompt) {
        String sanitized = prompt == null ? "" : prompt.trim();
        if (!translationEnabled || sanitized.isBlank()) {
            return sanitized;
        }
        try {
            String translated = requestTranslation(sanitized);
            String cleaned = clean(translated);
            if (!isUsable(cleaned, sanitized)) {
                logger.warn("Image prompt translation discarded (unusable output), keeping the original prompt");
                return sanitized;
            }
            logger.info("Image prompt translated for SDXL: '{}' -> '{}'", sanitized, cleaned);
            return cleaned;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return sanitized;
        } catch (Exception e) {
            logger.warn("Image prompt translation failed, keeping the original prompt: {}", e.getMessage());
            return sanitized;
        }
    }

    protected String requestTranslation(String prompt) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", translationModel);
        body.put("stream", false);
        body.put("think", false);
        body.put("keep_alive", keepAlive);
        ObjectNode optionsNode = body.putObject("options");
        optionsNode.put("temperature", 0.1);
        optionsNode.put("num_predict", 120);
        ArrayNode messages = body.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", INSTRUCTION);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", prompt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ollamaBaseUrl + "/api/chat"))
                .timeout(Duration.ofSeconds(translationTimeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Ollama returned HTTP " + response.statusCode());
        }
        JsonNode json = mapper.readTree(response.body());
        return json.path("message").path("content").asText("");
    }

    private static String clean(String value) {
        String cleaned = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (cleaned.length() >= 2) {
            char first = cleaned.charAt(0);
            char last = cleaned.charAt(cleaned.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
            }
        }
        return cleaned;
    }

    private static boolean isUsable(String translated, String original) {
        if (translated.isBlank()) {
            return false;
        }
        int maxLength = Math.max(300, original.length() * 4);
        return translated.length() <= maxLength;
    }
}
