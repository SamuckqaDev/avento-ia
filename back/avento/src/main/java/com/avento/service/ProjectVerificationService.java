package com.avento.service;

import com.avento.service.dto.ProjectCommandRequest;
import com.avento.service.dto.ProjectCommandResult;
import com.avento.service.dto.VerificationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * O elo que fecha o loop "editar → verificar → corrigir": detecta o comando canônico de
 * verificação do workspace (teste/build) e o executa via {@link ProjectCommandService}, devolvendo
 * um resultado enxuto (passou/falhou + resumo dos erros) que cabe no contexto do modelo local. O
 * agente chama isto depois de mexer no código; se falhar, lê os erros, corrige e chama de novo.
 */
@Service
public class ProjectVerificationService {

    // Ordem de preferência: o script mais "completo" primeiro. Todos precisam estar na allowlist
    // de scripts npm do ProjectCommandService.
    private static final List<String> NPM_VERIFY_PREFERENCE = List.of("validate", "build", "typecheck", "test", "lint");
    private static final int MAX_ERROR_CHARS = 4000;

    private final ProjectCommandService projectCommandService;
    private final WorkspaceAccessService workspaceAccessService;
    private final ObjectMapper mapper;

    public ProjectVerificationService(
            ProjectCommandService projectCommandService,
            WorkspaceAccessService workspaceAccessService,
            ObjectMapper mapper) {
        this.projectCommandService = projectCommandService;
        this.workspaceAccessService = workspaceAccessService;
        this.mapper = mapper;
    }

    public VerificationResult verify(String path) {
        Path dir = workspaceAccessService.requireAuthorized(path);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("O caminho de verificação precisa ser um diretório.");
        }

        ProjectCommandRequest request = detectCommand(dir);
        if (request == null) {
            return new VerificationResult(
                    false,
                    false,
                    "",
                    0,
                    false,
                    "Nenhum comando de verificação foi detectado. Esperado um package.json com um script"
                            + " conhecido (validate/build/typecheck/test/lint) ou um pom.xml (Maven).");
        }

        ProjectCommandResult result = projectCommandService.run(request);
        boolean ok = !result.timedOut() && result.exitCode() == 0;
        return new VerificationResult(
                true,
                ok,
                result.command(),
                result.exitCode(),
                result.timedOut(),
                ok ? "" : summarizeErrors(result.output()));
    }

    private ProjectCommandRequest detectCommand(Path dir) {
        Path packageJson = dir.resolve("package.json");
        if (Files.isRegularFile(packageJson)) {
            try {
                JsonNode scripts = mapper.readTree(packageJson.toFile()).path("scripts");
                for (String preferred : NPM_VERIFY_PREFERENCE) {
                    if (scripts.has(preferred)) {
                        return new ProjectCommandRequest(dir.toString(), "npm", preferred);
                    }
                }
            } catch (IOException ignored) {
                // package.json ilegível: cai para os próximos detectores.
            }
        }
        if (Files.isRegularFile(dir.resolve("pom.xml"))) {
            return new ProjectCommandRequest(dir.toString(), "maven", "test");
        }
        return null;
    }

    /**
     * Extrai as linhas relevantes de erro da saída (para não jogar 20k de log no contexto do modelo
     * pequeno). Se não achar linhas de erro reconhecíveis, devolve o rabo da saída.
     */
    private String summarizeErrors(String output) {
        if (output == null || output.isBlank()) {
            return "(o comando falhou sem produzir saída)";
        }
        StringBuilder errors = new StringBuilder();
        for (String line : output.split("\n")) {
            String lower = line.toLowerCase();
            if (lower.contains("error")
                    || lower.contains("fail")
                    || lower.contains("exception")
                    || lower.contains("cannot find")
                    || lower.contains("✗")
                    || lower.contains("build failure")) {
                errors.append(line).append('\n');
            }
        }
        String summary = errors.length() > 0 ? errors.toString() : output;
        if (summary.length() > MAX_ERROR_CHARS) {
            summary = "...\n" + summary.substring(summary.length() - MAX_ERROR_CHARS);
        }
        return summary;
    }
}
