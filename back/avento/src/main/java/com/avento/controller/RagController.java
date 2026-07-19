package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.*;
import com.avento.api.dto.BaseResponse;
import com.avento.api.dto.OperationResponse;
import com.avento.service.RagService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag")
@CrossOrigin(origins = "*")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/index")
    public ResponseEntity<BaseResponse<OperationResponse>> indexPaths(@RequestBody RagPathsRequest request) {
        List<String> paths = request.projectPaths();
        if (paths == null || paths.isEmpty()) {
            throw new IllegalArgumentException("projectPaths is required");
        }

        // Executa a indexação de forma assíncrona para não bloquear a requisição HTTP
        new Thread(() -> {
                    try {
                        ragService.indexProject(paths);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                })
                .start();

        return ApiResponses.accepted(new OperationResponse("Indexação iniciada em segundo plano."));
    }

    @PostMapping("/clear")
    public ResponseEntity<BaseResponse<OperationResponse>> clearPaths(@RequestBody RagPathsRequest request) {
        List<String> paths = request.projectPaths();
        if (paths == null || paths.isEmpty()) {
            return ApiResponses.ok(new OperationResponse("Nenhum projeto RAG para limpar."));
        }
        ragService.clearProjects(paths);
        return ApiResponses.ok(new OperationResponse("Contexto RAG removido."));
    }

    @PostMapping("/search")
    public ResponseEntity<BaseResponse<List<Document>>> searchContext(@RequestBody RagSearchRequest request) {
        String query = request.query();
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }

        List<String> projectPaths = new ArrayList<>();
        if (request.projectPaths() != null) {
            request.projectPaths().stream()
                    .filter(path -> path != null && !path.isBlank())
                    .forEach(projectPaths::add);
        }

        List<Document> results = ragService.searchContext(query, projectPaths);
        return ApiResponses.ok(results);
    }
}
