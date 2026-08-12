package com.avento.controller;

import com.avento.dto.*;
import com.avento.dto.BaseResponse;
import com.avento.dto.ConnectionResult;
import com.avento.dto.Context;
import com.avento.dto.ServerDescriptor;
import com.avento.dto.UnifiedToolCatalog;
import com.avento.dto.api.ApiResponses;
import com.avento.service.WorkspaceAccessService;
import com.avento.service.auth.AuthPrincipal;
import com.avento.service.mcp.McpServerCatalogService;
import com.avento.service.tools.ToolExecutionContext;
import com.avento.service.tools.UnifiedToolCatalogService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mcp/catalog")
public class McpCatalogController {

    private final McpServerCatalogService catalogService;
    private final WorkspaceAccessService workspaceAccessService;
    private final ToolExecutionContext executionContext;
    private final UnifiedToolCatalogService unifiedToolCatalogService;

    public McpCatalogController(
            McpServerCatalogService catalogService,
            WorkspaceAccessService workspaceAccessService,
            ToolExecutionContext executionContext,
            UnifiedToolCatalogService unifiedToolCatalogService) {
        this.catalogService = catalogService;
        this.workspaceAccessService = workspaceAccessService;
        this.executionContext = executionContext;
        this.unifiedToolCatalogService = unifiedToolCatalogService;
    }

    @GetMapping
    public ResponseEntity<BaseResponse<List<ServerDescriptor>>> catalog(
            @RequestParam(name = "workspace", required = false) List<String> workspaces,
            @RequestParam(name = "chatId", required = false) Long chatId,
            @AuthenticationPrincipal AuthPrincipal principal)
            throws Exception {
        List<ServerDescriptor> catalog = executionContext.call(
                context(principal, chatId), () -> catalogService.catalog(registerRoots(principal, workspaces)));
        return ApiResponses.ok(catalog);
    }

    /**
     * Catálogo operacional: capacidades nativas, MCPs instalados no host e ferramentas que o
     * Docker MCP já anunciou para o chat atual.
     *
     * <p>A chamada é somente leitura. Um servidor desligado aparece como disponível ou
     * indisponível com o motivo correspondente, mas não é iniciado apenas para preencher a tela.
     */
    @GetMapping("/tools")
    public ResponseEntity<BaseResponse<UnifiedToolCatalog>> tools(
            @RequestParam(name = "workspace", required = false) List<String> workspaces,
            @RequestParam(name = "chatId", required = false) Long chatId,
            @AuthenticationPrincipal AuthPrincipal principal)
            throws Exception {
        UnifiedToolCatalog tools = executionContext.call(
                context(principal, chatId), () -> unifiedToolCatalogService.catalog(registerRoots(principal, workspaces)));
        return ApiResponses.ok(tools);
    }

    @PostMapping("/connect")
    public ResponseEntity<BaseResponse<ConnectResponse>> connect(
            @RequestBody CatalogRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        try {
            return executionContext.call(context(principal, request.chatId()), () -> {
                List<ConnectionResult> results =
                        catalogService.connect(request.serverIds(), registerRoots(principal, request.projectPaths()));
                boolean connected = results.stream().anyMatch(ConnectionResult::connected);
                return ApiResponses.ok(new ConnectResponse(connected, results));
            });
        } catch (Exception exception) {
            throw new IllegalStateException("Could not connect MCP servers", exception);
        }
    }

    @PostMapping("/disconnect")
    public ResponseEntity<BaseResponse<DisconnectResponse>> disconnect(
            @RequestBody CatalogRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        try {
            return executionContext.call(context(principal, request.chatId()), () -> {
                catalogService.disconnect(request.serverIds());
                return ApiResponses.ok(new DisconnectResponse(request.serverIds()));
            });
        } catch (Exception exception) {
            throw new IllegalStateException("Could not disconnect MCP servers", exception);
        }
    }

    private List<String> registerRoots(AuthPrincipal principal, List<String> paths) {
        List<String> roots = new ArrayList<>();
        for (String path : paths == null ? List.<String>of() : paths) {
            roots.add(workspaceAccessService
                    .registerWorkspaceRoot(principal == null ? null : principal.userId(), path)
                    .toString());
        }
        return List.copyOf(roots);
    }

    private Context context(AuthPrincipal principal, Long chatId) {
        return new Context(principal == null ? null : principal.userId(), chatId, "");
    }
}
