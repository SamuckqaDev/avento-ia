package com.avento.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * Fechar a aba no meio de um stream SSE derruba o socket, e a escrita seguinte estoura com "Broken
 * pipe". Isso caía no tratador geral, que tentava responder um {@code BaseResponse} JSON num canal já
 * marcado como {@code text/event-stream} — sem conversor para isso, o próprio tratador estourava e a
 * exceção original ficava soterrada sob a segunda.
 */
class ApiExceptionHandlerSseTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    private HttpServletRequest requestTo(String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        return request;
    }

    @Test
    void naoTentaSerializarCorpoQuandoOClienteJaFoiEmbora() {
        AsyncRequestNotUsableException exception = new AsyncRequestNotUsableException(
                "ServletOutputStream failed to flush", new java.io.IOException("Broken pipe"));

        ResponseEntity<Void> resposta = handler.handleClientGoneAway(exception, requestTo("/api/ai/runs/run_1/events"));

        // Sem corpo: e a ausencia dele que impede o segundo erro, porque nao ha o que converter.
        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(resposta.getBody()).isNull();
    }

    /** Erro de verdade continua virando resposta de erro com corpo — o tratador geral segue valendo. */
    @Test
    void erroDeVerdadeContinuaRespondendoComCorpo() {
        var resposta = handler.handleUnexpectedException(new RuntimeException("falha real"), requestTo("/api/chats"));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resposta.getBody()).isNotNull();
    }
}
