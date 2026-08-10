package com.avento.controller;

import com.avento.dto.ApiErrorData;
import com.avento.dto.BaseResponse;
import com.avento.dto.SelectableTool;
import com.avento.dto.api.ApiCodes;
import com.avento.dto.api.ApiErrorResponses;
import com.avento.dto.api.ApiResponses;
import com.avento.dto.profile.AgentProfileCreateRequest;
import com.avento.dto.profile.AgentProfileResponse;
import com.avento.dto.profile.AgentProfileUpdateRequest;
import com.avento.model.AgentProfile;
import com.avento.service.agent.AgentProfileService;
import com.avento.service.agent.AllowedToolsValidator;
import com.avento.service.agent.SelectableToolCatalog;
import com.avento.service.auth.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
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

    private final SelectableToolCatalog selectableToolCatalog;

    private final AgentProfileService agentService;

    public AgentProfileController(AgentProfileService agentService, SelectableToolCatalog selectableToolCatalog) {
        this.selectableToolCatalog = selectableToolCatalog;
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

    /**
     * O que a pessoa pode escolher ao montar um agente.
     *
     * <p>Fica aqui, e não no {@code McpController}, porque é uma pergunta da tela de agentes:
     * "quais capacidades existem?". O {@code GET /api/mcp/tools} responde outra — "o que está
     * conectado agora" — e usá-lo para montar a tela esconderia toda ferramenta de container
     * desligado, que é justamente o que a pessoa mais quer poder escolher.
     */
    @GetMapping("/tools")
    public ResponseEntity<BaseResponse<List<SelectableTool>>> selectableTools() {
        return ApiResponses.ok(selectableToolCatalog.all());
    }

    /**
     * Ferramenta inexistente no perfil vira 400 com os nomes errados, não 500.
     *
     * <p>Fica no controller de agentes, e não no {@code ApiExceptionHandler} global, porque um
     * {@code IllegalArgumentException} genérico virar 400 em toda a API esconderia defeito de
     * programação atrás de "requisição inválida". Aqui o significado é específico e conhecido: quem
     * mandou pediu uma ferramenta que não existe, e a resposta diz quais.
     */
    @ExceptionHandler(AllowedToolsValidator.UnknownToolsException.class)
    public ResponseEntity<BaseResponse<ApiErrorData>> handleUnknownTools(
            AllowedToolsValidator.UnknownToolsException exception, HttpServletRequest request) {
        return ApiErrorResponses.response(
                request, HttpStatus.BAD_REQUEST, ApiCodes.INVALID_REQUEST, exception.getMessage());
    }
}
