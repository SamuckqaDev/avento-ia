package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.BaseResponse;
import com.avento.auth.security.AuthPrincipal;
import com.avento.service.provider.ModelProviderService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class OllamaController {

    private final ModelProviderService providerService;

    public OllamaController(ModelProviderService providerService) {
        this.providerService = providerService;
    }

    @GetMapping("/models")
    public ResponseEntity<BaseResponse<JsonNode>> getModels(
            @AuthenticationPrincipal AuthPrincipal principal) {
        JsonNode models = providerService.listAvailableModels(principal != null ? principal.userId() : null);
        return ApiResponses.ok(models);
    }
}
