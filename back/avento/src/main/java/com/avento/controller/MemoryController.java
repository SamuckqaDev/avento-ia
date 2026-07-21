package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.BaseResponse;
import com.avento.api.dto.UserMemoryCreateRequest;
import com.avento.api.dto.UserMemoryResponse;
import com.avento.api.dto.UserMemoryUpdateRequest;
import com.avento.auth.security.AuthPrincipal;
import com.avento.model.UserMemory;
import com.avento.service.UserMemoryService;
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

/** Memória híbrida do Avento: o usuário lista, adiciona, confirma sugestões do modelo e remove. */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final UserMemoryService memoryService;

    public MemoryController(UserMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping
    public ResponseEntity<BaseResponse<List<UserMemoryResponse>>> list(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponses.ok(memoryService.listAll(principal.userId()).stream()
                .map(UserMemoryResponse::from)
                .toList());
    }

    @PostMapping
    public ResponseEntity<BaseResponse<UserMemoryResponse>> add(
            @Valid @RequestBody UserMemoryCreateRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        String category = request.category() == null || request.category().isBlank()
                ? UserMemoryService.defaultCategory()
                : request.category();
        return ApiResponses.created(
                UserMemoryResponse.from(memoryService.addManual(principal.userId(), request.content(), category)));
    }

    @PutMapping("/{id}/confirm")
    public ResponseEntity<BaseResponse<UserMemoryResponse>> confirm(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        return respondOrNotFound(() -> memoryService.confirm(principal.userId(), id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<BaseResponse<UserMemoryResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody UserMemoryUpdateRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return respondOrNotFound(
                () -> memoryService.update(principal.userId(), id, request.content(), request.category()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<BaseResponse<Map<String, Boolean>>> delete(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        memoryService.delete(principal.userId(), id);
        return ApiResponses.ok(Map.of("deleted", true));
    }

    private ResponseEntity<BaseResponse<UserMemoryResponse>> respondOrNotFound(Supplier<UserMemory> action) {
        try {
            return ApiResponses.ok(UserMemoryResponse.from(action.get()));
        } catch (IllegalArgumentException notFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFound.getMessage());
        }
    }
}
