package com.avento.service.orchestration;

import com.avento.model.ChatRepository;
import com.avento.model.Message;
import com.avento.model.MessageRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Guarda a resposta que o servidor produziu quando ninguém estava ouvindo.
 *
 * <p>Quem persiste a resposta do assistente é o FRONTEND, depois de consumir o stream SSE. Enquanto o
 * navegador fica aberto isso funciona. Quando a conexão cai, não: o run
 * {@code run_8c4ab823} trabalhou 11 minutos, completou às 15:12:42, e a resposta não existe em lugar
 * nenhum — o cliente tinha desistido 69 segundos antes. O servidor fez o trabalho e o jogou fora
 * porque ninguém confirmou o recebimento.
 *
 * <p>Isto é uma REDE DE SEGURANÇA, não a troca do modelo de persistência. O caminho normal continua
 * sendo o frontend gravar; aqui só entra o que se perderia. Por isso a checagem é adiada: o frontend
 * grava logo depois do stream fechar, e escrever no mesmo instante criaria mensagem duplicada. Passado
 * o prazo, se ainda não há resposta depois da última pergunta do usuário, ela é gravada aqui.
 */
@Component
public class OrphanReplyRescue {

    private static final Logger logger = LoggerFactory.getLogger(OrphanReplyRescue.class);

    private final MessageRepository messageRepository;
    private final ChatRepository chatRepository;
    private final ObjectMapper mapper;
    private final Duration gracePeriod;
    private final Map<String, StringBuilder> textByRun = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> startedAt = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    @Autowired
    public OrphanReplyRescue(
            ObjectProvider<MessageRepository> messageRepositoryProvider,
            ObjectProvider<ChatRepository> chatRepositoryProvider,
            ObjectMapper mapper,
            @Value("${avento.agent.orphan-reply-grace:20s}") Duration gracePeriod) {
        this(messageRepositoryProvider.getIfAvailable(), chatRepositoryProvider.getIfAvailable(), mapper, gracePeriod);
    }

    /** Sem repositório vira no-op: é o que serve para quem monta o orquestrador à mão, nos testes. */
    OrphanReplyRescue(
            MessageRepository messageRepository,
            ChatRepository chatRepository,
            ObjectMapper mapper,
            Duration gracePeriod) {
        this.messageRepository = messageRepository;
        this.chatRepository = chatRepository;
        this.mapper = mapper;
        this.gracePeriod = gracePeriod;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "avento-orphan-reply");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    /** Acumula o texto que está indo para a tela, ignorando os eventos de ciclo de vida. */
    public void observe(String runId, String chunk) {
        if (runId == null || chunk == null || chunk.isBlank()) {
            return;
        }
        String content = contentOf(chunk);
        if (!content.isEmpty()) {
            textByRun.computeIfAbsent(runId, ignored -> new StringBuilder()).append(content);
        }
    }

    /** Marca o instante em que o run comecou, para saber depois o que e resposta DELE. */
    public void onRunStarted(String runId) {
        if (runId != null) {
            startedAt.put(runId, LocalDateTime.now());
        }
    }

    /** Ao fim do run, agenda a checagem. Descarta o texto quando não há onde gravar. */
    public void onRunFinished(String runId, Long chatId) {
        StringBuilder collected = textByRun.remove(runId);
        LocalDateTime started = startedAt.remove(runId);
        if (collected == null || chatId == null || messageRepository == null) {
            return;
        }
        String reply = collected.toString().trim();
        if (reply.isEmpty()) {
            return;
        }
        LocalDateTime marco = started == null ? LocalDateTime.now().minusMinutes(30) : started;
        scheduler.schedule(
                () -> rescueIfOrphan(runId, chatId, reply, marco), gracePeriod.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void rescueIfOrphan(String runId, Long chatId, String reply, LocalDateTime runStartedAt) {
        try {
            if (replyAlreadySaved(chatId, runStartedAt)) {
                return; // o frontend gravou; nada a fazer.
            }
            messageRepository.save(new Message(chatId, "assistant", reply));
            if (chatRepository != null) {
                chatRepository.findById(chatId).ifPresent(chat -> {
                    chat.touch();
                    chatRepository.save(chat);
                });
            }
            logger.info(
                    "Resposta do run {} recuperada para o chat {} ({} caracteres): o cliente não estava ouvindo",
                    runId,
                    chatId,
                    reply.length());
        } catch (Exception exception) {
            // Falhar aqui não pode derrubar nada: este caminho existe para salvar trabalho perdido,
            // e um erro dele não deve virar um segundo problema em cima do primeiro.
            logger.warn("Não foi possível recuperar a resposta do run {}", runId, exception);
        }
    }

    /**
     * Verdadeiro quando ja existe resposta do assistente posterior ao inicio DESTE run.
     *
     * <p>A primeira versao perguntava outra coisa: "a ultima mensagem do chat e do assistente?". Numa
     * conversa real isso quebrou na hora — o usuario mandou a pergunta SEGUINTE antes de vencer a
     * carencia de 20s, a ultima mensagem virou dele, e a rede de seguranca gravou uma duplicata da
     * resposta que o frontend ja tinha salvo. Poluir a conversa que se quer proteger e pior do que
     * nao proteger.
     *
     * <p>A pergunta certa e por RUN, nao por chat: se surgiu resposta depois de este run comecar, ela
     * e a resposta dele e nao ha nada a recuperar. Uma pergunta nova do usuario no meio nao muda isso.
     */
    private boolean replyAlreadySaved(Long chatId, LocalDateTime runStartedAt) {
        return messageRepository.findByChatIdOrderByTimestampAsc(chatId).stream()
                .filter(message -> "assistant".equals(message.getRole()))
                .anyMatch(message ->
                        message.getTimestamp() != null && message.getTimestamp().isAfter(runStartedAt));
    }

    /** O texto visível de um chunk no formato da OpenAI; vazio para evento de ciclo de vida. */
    private String contentOf(String chunk) {
        try {
            JsonNode root = mapper.readTree(chunk);
            if (root.has("avento_event")) {
                return "";
            }
            return root.path("choices").path(0).path("delta").path("content").asText("");
        } catch (Exception exception) {
            return "";
        }
    }
}
