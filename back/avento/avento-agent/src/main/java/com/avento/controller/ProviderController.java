package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.BaseResponse;
import com.avento.api.dto.ProviderSettingsResponse;
import com.avento.api.dto.ProviderSettingsUpdateRequest;
import com.avento.api.dto.ProviderTestRequest;
import com.avento.api.dto.ProviderTestResponse;
import com.avento.auth.security.AuthPrincipal;
import com.avento.service.provider.ModelProviderService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/providers")
public class ProviderController {

    private final ModelProviderService providerService;

    public ProviderController(ModelProviderService providerService) {
        this.providerService = providerService;
    }

    @GetMapping
    public ResponseEntity<BaseResponse<ProviderSettingsResponse>> getProviderSettings(
            @AuthenticationPrincipal AuthPrincipal principal) {
        ProviderSettingsResponse settings = providerService.getSettings(principal != null ? principal.userId() : null);
        return ApiResponses.ok(settings);
    }

    /**
     * Modelos que o PROVEDOR ATIVO oferece — Gemini, Anthropic, um DGX ou o Ollama local.
     *
     * <p>Vivia num {@code OllamaController} mapeado em {@code /api/models}. O nome mentia: a classe
     * so delegava para o servico de provedor, entao a rota nunca teve relacao com o Ollama. Achar
     * "ollama" no log ao depurar um problema de Gemini mandava procurar no lugar errado.
     */
    @GetMapping("/models")
    public ResponseEntity<BaseResponse<JsonNode>> getProviderModels(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponses.ok(providerService.listAvailableModels(principal != null ? principal.userId() : null));
    }

    @PutMapping
    public ResponseEntity<BaseResponse<ProviderSettingsResponse>> updateProviderSettings(
            @RequestBody ProviderSettingsUpdateRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        ProviderSettingsResponse settings =
                providerService.updateSettings(principal != null ? principal.userId() : null, request);
        return ApiResponses.ok(settings);
    }

    @DeleteMapping
    public ResponseEntity<BaseResponse<ProviderSettingsResponse>> disconnectProvider(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponses.ok(providerService.disconnect(principal != null ? principal.userId() : null));
    }

    @PostMapping("/test")
    public ResponseEntity<BaseResponse<ProviderTestResponse>> testProviderConnection(
            @RequestBody ProviderTestRequest request) {
        ProviderTestResponse result = providerService.testConnection(request);
        return ApiResponses.ok(result);
    }
}
