package com.avento.service.provider;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Cliente HTTP dos transportes de provedor, com os dois ajustes que todo streaming de nuvem precisa.
 *
 * <p>Existe para o motivo de cada ajuste ficar escrito UMA vez. Os três transportes precisavam da
 * mesma configuração, e repetir o código repetiria também o risco de alguém "limpar" uma das linhas
 * sem saber que ela conserta um bug concreto.
 */
final class ProviderWebClients {

    /** Resposta longa estoura o buffer padrão de 256KB e derruba a conversa no meio. */
    private static final int MAX_IN_MEMORY_BYTES = 8 * 1024 * 1024;

    private ProviderWebClients() {}

    /**
     * Cliente para chamada de streaming a um provedor.
     *
     * <p>O resolvedor de DNS é o da JVM, não o nativo do Netty: o nativo para macOS só existe para
     * x86_64 e, num Apple Silicon, a chamada morre com "Can't assign requested address" antes de
     * sair da máquina — parece erro de rede do provedor, e não é.
     */
    static WebClient streaming() {
        return WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .clientConnector(new ReactorClientHttpConnector(
                        reactor.netty.http.client.HttpClient.create().resolver(DefaultAddressResolverGroup.INSTANCE)))
                .build();
    }
}
