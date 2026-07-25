package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.BaseResponse;
import com.avento.api.dto.UsageSummary;
import com.avento.auth.security.AuthPrincipal;
import com.avento.service.TokenUsageService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/usage")
@RequiredArgsConstructor
public class UsageController {
    private final TokenUsageService tokenUsageService;

    @GetMapping("/summary")
    public ResponseEntity<BaseResponse<UsageSummary>> summary(
            @RequestParam(defaultValue = "7d") String range, @AuthenticationPrincipal AuthPrincipal principal) {
        UUID userId = principal == null ? null : principal.userId();
        return ApiResponses.ok(tokenUsageService.summary(userId, range));
    }
}
