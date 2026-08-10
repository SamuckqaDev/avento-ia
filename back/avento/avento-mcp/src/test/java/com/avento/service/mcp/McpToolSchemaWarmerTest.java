package com.avento.service.mcp;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * O aquecimento existe para a tela de criação de agente não nascer vazia — e não pode custar nada a
 * quem está esperando uma resposta.
 */
class McpToolSchemaWarmerTest {

    private final McpServerCatalogService catalog = mock(McpServerCatalogService.class);

    @Test
    void warmsTheCacheAfterTheApplicationIsReady() {
        when(catalog.warmSchemaCache()).thenReturn(4);

        new McpToolSchemaWarmer(catalog, true).warmInBackground();

        verify(catalog, timeout(5_000)).warmSchemaCache();
    }

    /**
     * Numa máquina apertada, gastar containers na subida é uma escolha que a pessoa pode recusar.
     * Desligado significa desligado — nem a thread nasce.
     */
    @Test
    void doesNothingWhenTurnedOff() throws Exception {
        new McpToolSchemaWarmer(catalog, false).warmInBackground();

        Thread.sleep(200);
        verify(catalog, never()).warmSchemaCache();
    }

    /**
     * <b>A propriedade que mais importa.</b> O aquecimento roda FORA da thread que dispara o evento.
     * Se rodasse dentro, subir quatro containers apareceria como demora na subida da aplicação para
     * quem abriu o app.
     */
    @Test
    void runsOffTheCallersThread() {
        Thread caller = Thread.currentThread();
        java.util.concurrent.atomic.AtomicReference<Thread> executedOn =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(catalog.warmSchemaCache()).thenAnswer(invocation -> {
            executedOn.set(Thread.currentThread());
            return 1;
        });

        new McpToolSchemaWarmer(catalog, true).warmInBackground();

        await().atMost(Duration.ofSeconds(5)).until(() -> executedOn.get() != null);
        org.assertj.core.api.Assertions.assertThat(executedOn.get()).isNotSameAs(caller);
    }

    /**
     * Falha no aquecimento não pode escapar: sem ele o cache volta a aprender na primeira conexão
     * real, que era o comportamento antes desta classe existir.
     */
    @Test
    void swallowsAFailureInsteadOfLettingItEscape() {
        when(catalog.warmSchemaCache()).thenThrow(new IllegalStateException("docker fora do ar"));

        new McpToolSchemaWarmer(catalog, true).warmInBackground();

        verify(catalog, timeout(5_000)).warmSchemaCache();
    }
}
