package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.BaseResponse;
import com.avento.api.dto.ProviderSettingsResponse;
import com.avento.api.dto.ProviderSettingsUpdateRequest;
import com.avento.api.dto.ProviderTestRequest;
import com.avento.api.dto.ProviderTestResponse;
import com.avento.auth.security.AuthPrincipal;
import com.avento.service.provider.ModelProviderService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    @PutMapping
    public ResponseEntity<BaseResponse<ProviderSettingsResponse>> updateProviderSettings(
            @RequestBody ProviderSettingsUpdateRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        ProviderSettingsResponse settings = providerService.updateSettings(principal != null ? principal.userId() : null, request);
        return ApiResponses.ok(settings);
    }

    @PostMapping("/test")
    public ResponseEntity<BaseResponse<ProviderTestResponse>> testProviderConnection(
            @RequestBody ProviderTestRequest request) {
        ProviderTestResponse result = providerService.testConnection(request);
        return ApiResponses.ok(result);
    }
}
