package com.avento.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.model.Message;
import com.avento.repository.ChatRepository;
import com.avento.repository.MessageRepository;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Quem grava a resposta do assistente é o frontend, depois de consumir o stream SSE. Quando a conexão
 * cai, ninguém grava: o run {@code run_8c4ab823} trabalhou 11 minutos, completou, e a resposta não
 * existia em lugar nenhum — o cliente tinha desistido 69 segundos antes do fim.
 */
class OrphanReplyRescueTest {

    private static final Long CHAT = 12L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String chunk(String texto) {
        return "{\"choices\":[{\"delta\":{\"content\":\"" + texto + "\"}}]}";
    }

    private OrphanReplyRescue rescueWith(MessageRepository repository) {
        ChatRepository chats = mock(ChatRepository.class);
        when(chats.findById(CHAT)).thenReturn(Optional.empty());
        // Prazo curto: o teste não pode esperar os 20s de produção para provar a regra.
        return new OrphanReplyRescue(repository, chats, MAPPER, Duration.ofMillis(50));
    }

    private Message mensagem(String papel) {
        return mensagem(papel, java.time.LocalDateTime.now().minusHours(1));
    }

    private Message mensagem(String papel, java.time.LocalDateTime quando) {
        Message m = new Message(CHAT, papel, "texto");
        m.setTimestamp(quando);
        return m;
    }

    @Test
    void gravaARespostaQuandoNinguemEstavaOuvindo() {
        MessageRepository repository = mock(MessageRepository.class);
        // A última mensagem é do USUÁRIO: a resposta se perdeu com o cliente.
        when(repository.findByChatIdOrderByTimestampAsc(CHAT)).thenReturn(List.of(mensagem("user")));
        OrphanReplyRescue rescue = rescueWith(repository);

        rescue.onRunStarted("run_1");
        rescue.observe("run_1", chunk("Onze minutos "));
        rescue.onRunStarted("run_1");
        rescue.observe("run_1", chunk("de trabalho."));
        rescue.onRunFinished("run_1", CHAT);

        ArgumentCaptor<Message> salva = ArgumentCaptor.forClass(Message.class);
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> verify(repository).save(salva.capture()));
        assertThat(salva.getValue().getRole()).isEqualTo("assistant");
        assertThat(salva.getValue().getContent()).isEqualTo("Onze minutos de trabalho.");
    }

    @Test
    void naoDuplicaQuandoOFrontendJaGravou() throws Exception {
        MessageRepository repository = mock(MessageRepository.class);
        OrphanReplyRescue rescue = rescueWith(repository);
        rescue.onRunStarted("run_2");
        // Resposta gravada DEPOIS de o run comecar: e a resposta dele, nao ha o que recuperar.
        when(repository.findByChatIdOrderByTimestampAsc(CHAT))
                .thenReturn(List.of(
                        mensagem("user", java.time.LocalDateTime.now().minusMinutes(1)),
                        mensagem("assistant", java.time.LocalDateTime.now().plusSeconds(5))));

        rescue.observe("run_2", chunk("resposta"));
        rescue.onRunFinished("run_2", CHAT);

        Thread.sleep(300);
        verify(repository, never()).save(any(Message.class));
    }

    /** Evento de ciclo de vida não é texto da resposta e não pode virar mensagem no chat. */
    @Test
    void ignoraEventosDeCicloDeVida() throws Exception {
        MessageRepository repository = mock(MessageRepository.class);
        when(repository.findByChatIdOrderByTimestampAsc(CHAT)).thenReturn(List.of(mensagem("user")));
        OrphanReplyRescue rescue = rescueWith(repository);

        rescue.onRunStarted("run_3");
        rescue.observe("run_3", "{\"avento_event\":{\"type\":\"agent.run.started\"}}");
        rescue.onRunFinished("run_3", CHAT);

        Thread.sleep(300);
        verify(repository, never()).save(any(Message.class));
    }

    /** Sem repositório o componente existe e não faz nada — é o modo dos testes do orquestrador. */
    @Test
    void naoEstouraSemRepositorio() {
        OrphanReplyRescue rescue =
                new OrphanReplyRescue((MessageRepository) null, (ChatRepository) null, MAPPER, Duration.ofMillis(10));

        rescue.observe("run_4", chunk("algo"));
        rescue.onRunFinished("run_4", CHAT);
    }

    /**
     * O defeito que so apareceu numa conversa real: a regra antiga era "a ultima mensagem do chat e
     * do assistente?". O usuario mandou a pergunta SEGUINTE antes de vencer a carencia de 20s, a
     * ultima virou dele, e a rede gravou uma DUPLICATA da resposta que o frontend ja tinha salvo.
     * Poluir a conversa que se quer proteger e pior do que nao proteger.
     */
    @Test
    void naoDuplicaQuandoOUsuarioJaMandouAProximaPergunta() throws Exception {
        MessageRepository repository = mock(MessageRepository.class);
        OrphanReplyRescue rescue = rescueWith(repository);
        rescue.onRunStarted("run_5");
        java.time.LocalDateTime depois = java.time.LocalDateTime.now().plusSeconds(5);
        // A resposta deste run FOI gravada, e por cima dela o usuario ja mandou a proxima pergunta.
        // Foi exatamente esta sequencia que fez a versao anterior gravar uma duplicata.
        when(repository.findByChatIdOrderByTimestampAsc(CHAT))
                .thenReturn(List.of(
                        mensagem("user", java.time.LocalDateTime.now().minusMinutes(1)),
                        mensagem("assistant", depois),
                        mensagem("user", depois.plusSeconds(1))));

        rescue.observe("run_5", chunk("resposta"));
        rescue.onRunFinished("run_5", CHAT);

        Thread.sleep(300);
        verify(repository, never()).save(any(Message.class));
    }
}
