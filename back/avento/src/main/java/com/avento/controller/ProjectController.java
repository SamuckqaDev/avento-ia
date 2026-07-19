package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.*;
import com.avento.api.dto.BaseResponse;
import com.avento.auth.security.AuthPrincipal;
import com.avento.service.ProjectAnalysisService;
import com.avento.service.ProjectCommandService;
import com.avento.service.dto.ProjectAnalysis;
import com.avento.service.dto.ProjectCommandRequest;
import com.avento.service.dto.ProjectCommandResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectAnalysisService projectAnalysisService;
    private final ProjectCommandService projectCommandService;

    public ProjectController(
            ProjectAnalysisService projectAnalysisService, ProjectCommandService projectCommandService) {
        this.projectAnalysisService = projectAnalysisService;
        this.projectCommandService = projectCommandService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<BaseResponse<ProjectAnalysis>> analyzeProject(
            @Valid @RequestBody ProjectAnalysisRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        ProjectAnalysis analysis = projectAnalysisService.analyze(principal.userId(), request.path());
        return ApiResponses.ok(analysis);
    }

    @PostMapping("/run")
    public ResponseEntity<BaseResponse<ProjectCommandResult>> runProjectCommand(
            @RequestBody ProjectCommandRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        ProjectCommandResult result = projectCommandService.run(principal.userId(), request);
        return ApiResponses.ok(result);
    }
}
