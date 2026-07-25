package com.avento.service.dto;

/**
 * Resultado de uma verificação de projeto (teste/build).
 *
 * @param detected     se um comando de verificação foi encontrado para o workspace
 * @param ok           true quando a verificação passou (saiu 0 e não estourou o tempo)
 * @param command      o comando efetivamente rodado (ex.: "npm run validate", "mvn test")
 * @param exitCode     código de saída do processo
 * @param timedOut     se o comando estourou o tempo limite
 * @param errorSummary resumo dos erros (linhas relevantes), vazio quando ok
 */
public record VerificationResult(
        boolean detected, boolean ok, String command, int exitCode, boolean timedOut, String errorSummary) {}
