package com.avento.service.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Validador estrito de JSON Schema para chamadas de ferramentas.
 * Intercepta chamadas de ferramenta com parâmetros inválidos ou incompletos e gera
 * uma instrução de reparo (repair prompt) detalhada para o LLM corrigir a chamada na próxima tentativa.
 */
@Service
public class ToolSchemaValidatorService {

    public record ValidationResult(boolean valid, List<String> errors, String repairPrompt) {
        public static ValidationResult ok() {
            return new ValidationResult(true, List.of(), "");
        }

        public static ValidationResult invalid(List<String> errors, String repairPrompt) {
            return new ValidationResult(false, errors, repairPrompt);
        }
    }

    /**
     * Valida os argumentos passados para uma ferramenta específica.
     */
    public ValidationResult validate(String toolName, JsonNode arguments) {
        if (toolName == null || toolName.isBlank()) {
            return ValidationResult.ok();
        }

        List<String> errors = new ArrayList<>();

        switch (toolName) {
            case "read_file":
            case "write_file":
            case "edit_file":
                if (arguments == null
                        || !arguments.has("path")
                        || arguments.path("path").asText("").isBlank()) {
                    errors.add("O parâmetro obrigatorio 'path' está ausente ou vazio.");
                }
                break;

            case "terminal_run":
            case "terminal_start":
                if (arguments == null || (!arguments.has("command") && !arguments.has("commandLine"))) {
                    errors.add("O parâmetro obrigatorio 'command' está ausente.");
                }
                break;

            case "open_url":
                if (arguments == null
                        || !arguments.has("url")
                        || arguments.path("url").asText("").isBlank()) {
                    errors.add("O parâmetro obrigatorio 'url' está ausente.");
                }
                break;

            case "generate_image":
                if (arguments == null
                        || !arguments.has("prompt")
                        || arguments.path("prompt").asText("").isBlank()) {
                    errors.add("O parâmetro obrigatorio 'prompt' está ausente.");
                }
                break;

            default:
                break;
        }

        if (errors.isEmpty()) {
            return ValidationResult.ok();
        }

        String repairPrompt = String.format(
                "[SISTEMA: ERRO DE PARAMETRO NA FERRAMENTA '%s']\n"
                        + "Sua chamada para a ferramenta '%s' falhou pela seguinte razão: %s.\n"
                        + "Por favor, reformule a chamada da ferramenta fornecendo os argumentos obrigatorios corretos em formato JSON estrito.",
                toolName, toolName, String.join("; ", errors));

        return ValidationResult.invalid(errors, repairPrompt);
    }
}
