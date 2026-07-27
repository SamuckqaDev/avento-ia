package com.avento.api.dto;

import java.util.List;

/**
 * Resultado do teste de conexao com o provedor.
 *
 * <p>Devolve os MODELOS encontrados, nao so um "deu certo": e essa lista que alimenta o seletor. Sem
 * ela, o usuario teria de digitar o nome do modelo de cabeca — que foi como um nome chumbado no
 * codigo acabou salvo e gerando 404 na primeira mensagem.
 */
public record ProviderTestResponse(boolean success, String message, long latencyMs, List<String> models) {

    public ProviderTestResponse(boolean success, String message, long latencyMs) {
        this(success, message, latencyMs, List.of());
    }
}
