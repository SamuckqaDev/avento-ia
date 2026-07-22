package com.avento.service;

import com.avento.model.Message;
import com.avento.repository.ChatRepository;
import com.avento.repository.MessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Extrai automaticamente memória de longo prazo a partir do histórico de uma conversa: em vez de
 * torcer para o modelo chamar a ferramenta {@code remember} sozinho (coisa que um modelo local
 * pequeno quase nunca faz), aqui um passo dedicado LÊ a conversa e propõe fatos duráveis. As
 * propostas entram como PENDENTES ({@link UserMemoryService#suggest}) — o usuário confirma.
 *
 * <p>Roda em background, fora do caminho da resposta, e é estrangulada (throttle + mínimo de
 * mensagens novas) porque cada extração custa uma inferência extra numa máquina local com RAM curta.
 */
@Service
public class MemoryExtractionService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryExtractionService.class);
    private static final int MAX_TRACKED_CHATS = 500;

    private static final String INSTRUCTION =
            com.avento.service.support.PromptResources.load("agent/prompts/memory-extraction.md");

    private final ObjectMapper mapper;
    private final ChatRepository chatRepository;
    private final MessageRepository messageRepository;
    private final UserMemoryService userMemoryService;
    private final String ollamaBaseUrl;
    private final String model;
    private final String keepAlive;
    private final boolean enabled;
    private final long timeoutSeconds;
    private final int minNewMessages;
    private final long throttleSeconds;
    private final int contextMessages;
    private final int maxSuggestionsPerRun;
    // Mesmo num_ctx do chat: evita que o Ollama recarregue o modelo alternando entre as duas janelas.
    private final int numCtx;
    private final HttpClient httpClient;

    // Throttle por chat: última extração (epoch em segundos) e a contagem de mensagens naquele
    // momento, para não reprocessar a mesma conversa a cada turno. Estado em memória basta num
    // assistente local de processo único.
    private final Map<ChatOwner, ExtractionMark> lastRunByChat = new ConcurrentHashMap<>();

    // Uma única thread daemon: extrações são serializadas e nunca competem em paralelo pela GPU/RAM.
    private final ExecutorService executor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32),
            runnable -> {
                Thread thread = new Thread(runnable, "memory-extraction");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    public MemoryExtractionService(
            ObjectMapper mapper,
            ChatRepository chatRepository,
            MessageRepository messageRepository,
            UserMemoryService userMemoryService,
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
            @Value("${avento.memory.extraction-model:${avento.agent.default-model:qwen3:8b}}") String model,
            @Value("${avento.agent.keep-alive:30m}") String keepAlive,
            @Value("${avento.memory.extraction-enabled:true}") boolean enabled,
            @Value("${avento.memory.extraction-timeout-seconds:60}") long timeoutSeconds,
            @Value("${avento.memory.extraction-min-new-messages:4}") int minNewMessages,
            @Value("${avento.memory.extraction-throttle-seconds:180}") long throttleSeconds,
            @Value("${avento.memory.extraction-context-messages:12}") int contextMessages,
            @Value("${avento.memory.extraction-max-suggestions:4}") int maxSuggestionsPerRun,
            @Value("${avento.agent.num-ctx:16384}") int numCtx) {
        this.mapper = mapper;
        this.chatRepository = chatRepository;
        this.messageRepository = messageRepository;
        this.userMemoryService = userMemoryService;
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.model = model;
        this.keepAlive = keepAlive;
        this.enabled = enabled;
        this.timeoutSeconds = timeoutSeconds;
        this.minNewMessages = Math.max(2, minNewMessages);
        this.throttleSeconds = Math.max(0, throttleSeconds);
        this.contextMessages = Math.max(4, contextMessages);
        this.maxSuggestionsPerRun = Math.max(1, maxSuggestionsPerRun);
        this.numCtx = Math.max(2048, numCtx);
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Dispara a extração em background se passar o throttle. Nunca lança nem bloqueia o chamador. */
    public void maybeExtractAsync(UUID userId, Long chatId) {
        if (!enabled || userId == null || chatId == null) {
            return;
        }
        try {
            executor.execute(() -> safeExtract(userId, chatId));
        } catch (RuntimeException rejected) {
            logger.debug("Extração de memória não agendada para o chat {}", chatId, rejected);
        }
    }

    private void safeExtract(UUID userId, Long chatId) {
        try {
            extract(userId, chatId);
        } catch (Exception exception) {
            logger.debug("Extração de memória falhou para o chat {}; ignorando", chatId, exception);
        }
    }

    // Package-private: o trabalho síncrono da extração, chamado pela thread de background e
    // exercitado diretamente pelos testes (sem depender de temporização de executor).
    void extract(UUID userId, Long chatId) throws Exception {
        if (chatRepository.findByIdAndUserId(chatId, userId).isEmpty()) {
            logger.debug("Extração ignorada: chat {} não pertence ao usuário autenticado", chatId);
            return;
        }
        List<Message> messages = messageRepository.findByChatIdOrderByTimestampAsc(chatId);
        ChatOwner owner = new ChatOwner(userId, chatId);
        if (!shouldRun(owner, messages.size())) {
            return;
        }
        String conversation = formatConversation(messages);
        if (conversation.isBlank()) {
            return;
        }

        // Marca ANTES de chamar o modelo: mesmo que a inferência demore, um segundo turno não
        // dispara outra extração concorrente da mesma conversa.
        rememberExtractionMark(owner, messages.size());

        String raw = requestExtraction(conversation);
        List<String> facts = parseFacts(raw);
        int saved = 0;
        for (String fact : facts) {
            if (userMemoryService
                    .suggest(userId, fact, UserMemoryService.defaultCategory())
                    .saved()) {
                saved++;
            }
        }
        if (saved > 0) {
            logger.info("Extração de memória: {} sugestão(ões) nova(s) para o chat {}", saved, chatId);
        }
    }

    private boolean shouldRun(ChatOwner owner, int messageCount) {
        ExtractionMark mark = lastRunByChat.get(owner);
        if (mark == null) {
            return messageCount >= minNewMessages;
        }
        boolean enoughNew = messageCount - mark.messageCount() >= minNewMessages;
        boolean cooledDown = Instant.now().getEpochSecond() - mark.epochSeconds() >= throttleSeconds;
        return enoughNew && cooledDown;
    }

    private void rememberExtractionMark(ChatOwner owner, int messageCount) {
        if (!lastRunByChat.containsKey(owner) && lastRunByChat.size() >= MAX_TRACKED_CHATS) {
            ChatOwner oldestOwner = null;
            long oldestEpoch = Long.MAX_VALUE;
            for (Map.Entry<ChatOwner, ExtractionMark> entry : lastRunByChat.entrySet()) {
                if (entry.getValue().epochSeconds() < oldestEpoch) {
                    oldestOwner = entry.getKey();
                    oldestEpoch = entry.getValue().epochSeconds();
                }
            }
            if (oldestOwner != null) {
                lastRunByChat.remove(oldestOwner);
            }
        }
        lastRunByChat.put(owner, new ExtractionMark(Instant.now().getEpochSecond(), messageCount));
    }

    private String formatConversation(List<Message> messages) {
        int from = Math.max(0, messages.size() - contextMessages);
        StringBuilder builder = new StringBuilder();
        for (Message message : messages.subList(from, messages.size())) {
            String role = message.getRole() == null ? "user" : message.getRole();
            if ("system".equalsIgnoreCase(role)) {
                continue;
            }
            String label = "assistant".equalsIgnoreCase(role) ? "Avento" : "Usuário";
            String content =
                    message.getContent() == null ? "" : message.getContent().strip();
            if (content.isEmpty()) {
                continue;
            }
            if (content.length() > 600) {
                content = content.substring(0, 600).strip() + "…";
            }
            builder.append(label).append(": ").append(content).append("\n");
        }
        return builder.toString().strip();
    }

    /** Quebra a saída do modelo em fatos limpos, descartando "NADA", marcadores e excesso. */
    List<String> parseFacts(String raw) {
        List<String> facts = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return facts;
        }
        for (String line : raw.split("\\r?\\n")) {
            String fact = line.strip().replaceFirst("^[-*•\\d.\\)\\s]+", "").strip();
            // "NOTHING" is the English sentinel from the prompt; "NADA" is kept for robustness in case
            // the local model answers in Portuguese.
            if (fact.isEmpty() || fact.equalsIgnoreCase("NOTHING") || fact.equalsIgnoreCase("NADA")) {
                continue;
            }
            facts.add(fact);
            if (facts.size() >= maxSuggestionsPerRun) {
                break;
            }
        }
        return facts;
    }

    protected String requestExtraction(String conversation) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.put("think", false);
        body.put("keep_alive", keepAlive);
        ObjectNode optionsNode = body.putObject("options");
        optionsNode.put("temperature", 0.1);
        optionsNode.put("num_predict", 200);
        optionsNode.put("num_ctx", numCtx);
        ArrayNode messages = body.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", INSTRUCTION);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", conversation);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ollamaBaseUrl + "/api/chat"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Ollama retornou HTTP " + response.statusCode());
        }
        JsonNode json = mapper.readTree(response.body());
        return json.path("message").path("content").asText("");
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private record ChatOwner(UUID userId, Long chatId) {}

    private record ExtractionMark(long epochSeconds, int messageCount) {}
}
