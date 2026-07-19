package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.BaseResponse;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/api/health")
    public ResponseEntity<BaseResponse<Map<String, String>>> health() {
        return ApiResponses.ok(Map.of(
                "status", "ok",
                "service", "avento-local-ai",
                "time", Instant.now().toString()));
    }
}
