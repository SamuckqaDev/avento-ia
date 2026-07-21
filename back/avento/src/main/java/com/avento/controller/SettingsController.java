package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.BaseResponse;
import com.avento.api.dto.UserSettingsRequest;
import com.avento.api.dto.UserSettingsResponse;
import com.avento.auth.security.AuthPrincipal;
import com.avento.service.UserSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final UserSettingsService settingsService;

    public SettingsController(UserSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public ResponseEntity<BaseResponse<UserSettingsResponse>> getSettings(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponses.ok(settingsService.get(principal.userId()));
    }

    @PutMapping
    public ResponseEntity<BaseResponse<UserSettingsResponse>> updateSettings(
            @RequestBody UserSettingsRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponses.ok(settingsService.update(principal.userId(), request));
    }

    @PutMapping("/defaults")
    public ResponseEntity<BaseResponse<UserSettingsResponse>> restoreDefaults(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponses.ok(settingsService.restoreDefaults(principal.userId()));
    }
}
