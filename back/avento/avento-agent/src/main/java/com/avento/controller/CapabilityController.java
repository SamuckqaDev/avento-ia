package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.*;
import com.avento.api.dto.BaseResponse;
import com.avento.service.tools.ToolCapability;
import com.avento.service.tools.ToolCapabilityRegistry;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/capabilities")
public class CapabilityController {

    private final ToolCapabilityRegistry toolRegistry;

    public CapabilityController(ToolCapabilityRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    public ResponseEntity<BaseResponse<CapabilityResponse>> listCapabilities() {
        List<ToolCapabilityResponse> tools = toolRegistry.all().stream()
                .sorted(Comparator.comparing(ToolCapability::category).thenComparing(ToolCapability::name))
                .map(ToolCapabilityResponse::from)
                .toList();
        return ApiResponses.ok(new CapabilityResponse(tools));
    }
}
