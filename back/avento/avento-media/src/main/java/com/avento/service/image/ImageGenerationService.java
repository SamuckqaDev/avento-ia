package com.avento.service.image;

import com.avento.service.ComfyUiImageService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ImageGenerationService implements ImageGenerator {

    private final ComfyUiImageService comfyUiImageService;
    private final ImagePromptTranslator promptTranslator;
    private final ObjectMapper mapper;

    // ObjectProvider e nao injecao direta: o modulo de midia compila e roda sem quem guarda a
    // configuracao, e os testes desta classe nao precisam de banco para existir.
    private final ObjectProvider<ConfiguredImageModel> configuredImageModelProvider;

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${avento.image.default-model:comfyui:RealVisXL_V5.0_fp16.safetensors}")
    private String defaultImageModel;

    @Override
    public ObjectNode generate(Map<String, Object> payload) {
        String prompt = requiredString(payload, "prompt").trim();
        String model = resolveModel(payload);
        ImageGenerationOptions imageOptions = ImageGenerationOptions.from(payload);
        String size = fallbackString(optionalString(payload, "size"), imageOptions.size());
        String englishPrompt = promptTranslator.toEnglish(prompt);
        ImagePromptPlan promptPlan = ImagePromptPlanner.plan(englishPrompt, imageOptions);

        if (comfyUiImageService.shouldUseComfy(model)) {
            ObjectNode comfyResult = comfyUiImageService.generateImage(promptPlan, model, size, imageOptions);
            if ("success".equals(comfyResult.path("status").asText())
                    || comfyUiImageService.isComfyModel(model)
                    || comfyUiImageService.isComfyOnly()) {
                return comfyResult;
            }
        }

        return generateWithOllama(prompt, model, size, promptPlan, imageOptions);
    }

    /**
     * Modelo da imagem: o que a chamada pediu, senão o escolhido nas configurações, senão o do YAML.
     *
     * <p>O meio deste caminho não existia. O campo "modelo de imagem" da tela era gravado no banco e
     * {@code activeImageModel} não tinha um único chamador, então escolher um modelo ali não mudava
     * nada — toda geração caía no {@code avento.image.default-model} do arquivo.
     */
    @Override
    public String resolveModel(Map<String, Object> payload) {
        String requested = optionalString(payload, "model");
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        ConfiguredImageModel configuredImageModel = configuredImageModelProvider.getIfAvailable();
        String configured = configuredImageModel == null
                ? null
                : configuredImageModel.preferred().orElse(null);
        return fallbackString(configured, defaultImageModel);
    }

    @Override
    public void cancel(Thread worker, String model) {
        if (worker != null && comfyUiImageService.shouldUseComfy(model)) {
            comfyUiImageService.cancelImageGeneration(worker);
        }
        if (worker != null) {
            worker.interrupt();
        }
    }

    private ObjectNode generateWithOllama(
            String prompt, String model, String size, ImagePromptPlan promptPlan, ImageGenerationOptions imageOptions) {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("prompt", promptPlan.positivePrompt());
        requestBody.put("size", size);
        requestBody.put("response_format", "b64_json");
        requestBody.put("n", 1);
        if (imageOptions.seed() != null) {
            requestBody.put("seed", imageOptions.seed());
        }

        ObjectNode result = mapper.createObjectNode();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(ollamaBaseUrl) + "/v1/images/generations"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                result.put("status", "failed");
                String responseBodyText = truncateText(response.body(), 1000);
                result.put("details", responseBodyText);
                if (isOllamaMemoryError(responseBodyText)) {
                    result.put("error", "O modelo de imagem nao coube na memoria disponivel do Ollama.");
                    result.put(
                            "hint",
                            "Feche apps pesados ou configure AVENTO_IMAGE_DEFAULT_MODEL com outro modelo de imagem que ja exista no seu Ollama.");
                } else {
                    result.put("error", "Ollama image generation failed with HTTP " + response.statusCode() + ".");
                    result.put(
                            "hint",
                            "Confirme se o modelo de imagem configurado esta instalado no Ollama: ollama pull "
                                    + model);
                }
                return result;
            }

            JsonNode responseBody = mapper.readTree(response.body());
            String base64Image =
                    responseBody.path("data").path(0).path("b64_json").asText("");
            if (base64Image.isBlank()) {
                result.put("status", "failed");
                result.put("error", "Ollama did not return b64_json image data.");
                result.put("hint", "Confirme se o modelo selecionado suporta /v1/images/generations.");
                return result;
            }

            byte[] imageBytes = Base64.getDecoder().decode(base64Image);
            Path outputDirectory = Paths.get(System.getProperty("user.home"), "Pictures", "Avento Generated Images");
            Files.createDirectories(outputDirectory);
            String filename = "avento-image-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".png";
            Path outputPath = outputDirectory.resolve(filename);
            Files.write(outputPath, imageBytes);

            result.put("status", "success");
            result.put("path", outputPath.toString());
            result.put("sizeBytes", imageBytes.length);
            result.put("model", model);
            result.put("size", size);
            result.put("prompt", prompt);
            result.put("enhancedPrompt", promptPlan.positivePrompt());
            result.put("negativePrompt", promptPlan.negativePrompt());
            result.put("qualityPreset", imageOptions.qualityPreset());
            result.put("subjectType", imageOptions.subjectType());
            result.put("subjectCount", promptPlan.subjectCount());
            if (imageOptions.seed() != null) {
                result.put("seed", imageOptions.seed());
            }
            result.put("message", "Imagem gerada e salva em " + outputPath);
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            result.put("status", "failed");
            result.put("error", "A geração de imagem foi cancelada.");
            return result;
        } catch (IllegalArgumentException exception) {
            result.put("status", "failed");
            result.put("error", "Invalid image data returned by Ollama: " + exception.getMessage());
            return result;
        } catch (Exception exception) {
            result.put("status", "failed");
            result.put("error", "Image generation failed: " + exception.getMessage());
            if (isOllamaMemoryError(exception.getMessage())) {
                result.put(
                        "hint",
                        "Feche apps pesados ou use AVENTO_IMAGE_DEFAULT_MODEL com um modelo de imagem menor ja instalado.");
            } else {
                result.put("hint", "Confirme se o Ollama esta ativo e se o modelo de imagem esta instalado.");
            }
            return result;
        }
    }

    private String requiredString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return text;
    }

    private String optionalString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof String text ? text : "";
    }

    private String fallbackString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean isOllamaMemoryError(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase();
        return normalized.contains("requires more memory")
                || normalized.contains("not enough memory")
                || normalized.contains("insufficient memory")
                || normalized.contains("out of memory")
                || normalized.contains("memoria")
                || normalized.contains("memória");
    }

    private String truncateText(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars) + "...";
    }

    private String trimTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
