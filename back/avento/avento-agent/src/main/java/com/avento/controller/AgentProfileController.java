package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.AgentProfileCreateRequest;
import com.avento.api.dto.AgentProfileResponse;
import com.avento.api.dto.AgentProfileUpdateRequest;
import com.avento.api.dto.BaseResponse;
import com.avento.auth.security.AuthPrincipal;
import com.avento.model.AgentProfile;
import com.avento.service.AgentProfileService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** CRUD de agentes especializados do usuário. Tudo escopado por {@code principal.userId()}. */
@RestController
@RequestMapping("/api/agents")
public class AgentProfileController {

    private final AgentProfileService agentService;

    public AgentProfileController(AgentProfileService agentService) {
        this.agentService = agentService;
    }

    @GetMapping
    public ResponseEntity<BaseResponse<List<AgentProfileResponse>>> list(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponses.ok(agentService.list(principal.userId()).stream()
                .map(AgentProfileResponse::from)
                .toList());
    }

    @PostMapping
    public ResponseEntity<BaseResponse<AgentProfileResponse>> create(
            @Valid @RequestBody AgentProfileCreateRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponses.created(AgentProfileResponse.from(agentService.create(
                principal.userId(),
                request.name(),
                request.specialty(),
                request.systemInstructions(),
                request.allowedTools(),
                request.triggers(),
                request.model(),
                request.isDefault())));
    }

    @PutMapping("/{id}")
    public ResponseEntity<BaseResponse<AgentProfileResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody AgentProfileUpdateRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return respondOrNotFound(() -> agentService.update(
                principal.userId(),
                id,
                request.name(),
                request.specialty(),
                request.systemInstructions(),
                request.allowedTools(),
                request.triggers(),
                request.model(),
                request.isDefault()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<BaseResponse<Map<String, Boolean>>> delete(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        agentService.delete(principal.userId(), id);
        return ApiResponses.ok(Map.of("deleted", true));
    }

    private ResponseEntity<BaseResponse<AgentProfileResponse>> respondOrNotFound(Supplier<AgentProfile> action) {
        try {
            return ApiResponses.ok(AgentProfileResponse.from(action.get()));
        } catch (IllegalArgumentException notFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFound.getMessage());
        }
    }
}
