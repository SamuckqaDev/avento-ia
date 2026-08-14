package com.avento.controller;

import com.avento.dto.BaseResponse;
import com.avento.dto.ObsidianVaultStatus;
import com.avento.dto.RagSearchRequest;
import com.avento.dto.api.ApiResponses;
import com.avento.service.auth.AuthPrincipal;
import com.avento.service.rag.ObsidianKnowledgeService;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated API for the single local Obsidian knowledge source. */
@RestController
@RequestMapping("/api/knowledge/obsidian")
public class ObsidianKnowledgeController {

    private final ObsidianKnowledgeService knowledgeService;

    public ObsidianKnowledgeController(ObsidianKnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping
    public ResponseEntity<BaseResponse<ObsidianVaultStatus>> status(@AuthenticationPrincipal AuthPrincipal principal) {
        requireAuthenticated(principal);
        return ApiResponses.ok(knowledgeService.status());
    }

    @PostMapping("/initialize")
    public ResponseEntity<BaseResponse<ObsidianVaultStatus>> initialize(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAuthenticated(principal);
        return ApiResponses.accepted(knowledgeService.initialize());
    }

    @PostMapping("/reindex")
    public ResponseEntity<BaseResponse<ObsidianVaultStatus>> reindex(@AuthenticationPrincipal AuthPrincipal principal) {
        requireAuthenticated(principal);
        return ApiResponses.accepted(knowledgeService.reindex());
    }

    @PostMapping("/search")
    public ResponseEntity<BaseResponse<List<Document>>> search(
            @RequestBody RagSearchRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAuthenticated(principal);
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        return ApiResponses.ok(knowledgeService.search(request.query()));
    }

    private void requireAuthenticated(AuthPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new SecurityException("Usuário autenticado é obrigatório para acessar o conhecimento local.");
        }
    }
}
