package com.avento.service.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Resolves the generation preset for a checkpoint. Bundled presets ship in
 * {@code comfyui/model-presets.json}; the user can add or override entries in a
 * local file (default {@code ~/.avento/image-presets.json}, never committed),
 * which is re-read on every generation so edits apply without restart. Local
 * entries win, first match wins, and a "*" entry acts as catch-all.
 */
@Service
public class ImageModelPresetCatalog {

    private static final Logger logger = LoggerFactory.getLogger(ImageModelPresetCatalog.class);
    private static final String BUNDLED_RESOURCE = "comfyui/model-presets.json";
    private static final ImageModelPreset FALLBACK = new ImageModelPreset(
            "fallback",
            List.of("*"),
            "dpmpp_2m",
            "karras",
            Map.of("draft", 14, "balanced", 20, "quality", 24),
            Map.of("draft", 4, "balanced", 6, "quality", 8),
            Map.of("draft", 6.5, "balanced", 6.0, "quality", 5.8),
            Map.of(),
            "tags");

    private final ObjectMapper mapper;
    private final String localPresetsFile;
    private volatile List<ImageModelPreset> bundledPresets;

    public ImageModelPresetCatalog(
            ObjectMapper mapper,
            @Value("${avento.image.presets-file:${user.home}/.avento/image-presets.json}") String localPresetsFile) {
        this.mapper = mapper;
        this.localPresetsFile = localPresetsFile == null ? "" : localPresetsFile.trim();
    }

    public ImageModelPreset forModel(String model) {
        String normalized = normalizeModel(model);
        for (ImageModelPreset preset : localPresets()) {
            if (preset.matches(normalized)) {
                logger.info("Image preset '{}' (local) selected for model {}", preset.name(), model);
                return preset;
            }
        }
        for (ImageModelPreset preset : bundled()) {
            if (preset.matches(normalized)) {
                logger.info("Image preset '{}' selected for model {}", preset.name(), model);
                return preset;
            }
        }
        return FALLBACK;
    }

    private static String normalizeModel(String model) {
        String normalized = model == null ? "" : model.trim();
        if (normalized.startsWith("comfyui:")) {
            normalized = normalized.substring("comfyui:".length());
        }
        return normalized.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private List<ImageModelPreset> bundled() {
        List<ImageModelPreset> cached = bundledPresets;
        if (cached != null) {
            return cached;
        }
        try (var inputStream = new ClassPathResource(BUNDLED_RESOURCE).getInputStream()) {
            cached = parse(mapper.readTree(inputStream));
        } catch (IOException exception) {
            logger.warn("Could not load bundled image presets; using the built-in fallback", exception);
            cached = List.of();
        }
        bundledPresets = cached;
        return cached;
    }

    private List<ImageModelPreset> localPresets() {
        if (localPresetsFile.isBlank()) {
            return List.of();
        }
        Path path = Paths.get(localPresetsFile);
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try {
            return parse(mapper.readTree(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException exception) {
            logger.warn("Ignoring invalid local image presets at {}: {}", path, exception.getMessage());
            return List.of();
        }
    }

    private List<ImageModelPreset> parse(JsonNode root) {
        List<ImageModelPreset> presets = new ArrayList<>();
        for (JsonNode node : root.path("presets")) {
            List<String> match = new ArrayList<>();
            node.path("match").forEach(candidate -> match.add(candidate.asText("")));
            presets.add(new ImageModelPreset(
                    node.path("name").asText(""),
                    match,
                    node.path("sampler").asText(""),
                    node.path("scheduler").asText(""),
                    intMap(node.path("steps")),
                    intMap(node.path("refinementSteps")),
                    doubleMap(node.path("cfg")),
                    intMap(node.path("longEdge")),
                    node.path("promptStyle").asText("")));
        }
        return List.copyOf(presets);
    }

    private static Map<String, Integer> intMap(JsonNode node) {
        Map<String, Integer> values = new HashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().canConvertToInt()) {
                values.put(entry.getKey(), entry.getValue().asInt());
            }
        });
        return values;
    }

    private static Map<String, Double> doubleMap(JsonNode node) {
        Map<String, Double> values = new HashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().isNumber()) {
                values.put(entry.getKey(), entry.getValue().asDouble());
            }
        });
        return values;
    }
}
