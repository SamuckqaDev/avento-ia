package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.BaseResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
public class OllamaController {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public OllamaController(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
            ObjectMapper objectMapper) {
        this.webClient = WebClient.builder().baseUrl(ollamaBaseUrl).build();
        this.objectMapper = objectMapper;
    }

    @GetMapping("/models")
    public ResponseEntity<BaseResponse<JsonNode>> getModels() throws Exception {
        String response = webClient
                .get()
                .uri("/v1/models")
                .retrieve()
                .bodyToMono(String.class)
                .block();
        return ApiResponses.ok(objectMapper.readTree(response));
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody Map<String, Object> payload) {
        return webClient
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToFlux(String.class);
    }
}
