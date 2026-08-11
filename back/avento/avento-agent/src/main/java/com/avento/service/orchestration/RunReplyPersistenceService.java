package com.avento.service.orchestration;

import com.avento.model.ChatRepository;
import com.avento.model.Message;
import com.avento.model.MessageRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Persists the visible reply produced by an asynchronous run before its terminal event is published.
 *
 * <p>Redis remains the replayable transport for deltas. PostgreSQL is the durable source of the
 * conversation: a browser disconnect must never decide whether an agent reply exists.
 */
@Service
public class RunReplyPersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(RunReplyPersistenceService.class);

    private final MessageRepository messageRepository;
    private final ChatRepository chatRepository;
    private final ObjectMapper mapper;
    private final Map<String, StringBuilder> textByRun = new ConcurrentHashMap<>();

    @Autowired
    public RunReplyPersistenceService(
            ObjectProvider<MessageRepository> messageRepositoryProvider,
            ObjectProvider<ChatRepository> chatRepositoryProvider,
            ObjectMapper mapper) {
        this(messageRepositoryProvider.getIfAvailable(), chatRepositoryProvider.getIfAvailable(), mapper);
    }

    RunReplyPersistenceService(
            MessageRepository messageRepository, ChatRepository chatRepository, ObjectMapper mapper) {
        this.messageRepository = messageRepository;
        this.chatRepository = chatRepository;
        this.mapper = mapper;
    }

    public void observe(String runId, String chunk) {
        if (runId == null || runId.isBlank() || chunk == null || chunk.isBlank()) {
            return;
        }
        String content = visibleContent(chunk);
        if (!content.isEmpty()) {
            textByRun.computeIfAbsent(runId, ignored -> new StringBuilder()).append(content);
        }
    }

    /** Saves exactly one final assistant message for this run and returns whether a reply exists. */
    @Transactional
    public boolean persistCompletedReply(String runId, Long chatId) {
        String reply = takeReply(runId);
        if (reply.isBlank() || chatId == null || messageRepository == null) {
            return existingReply(runId).isPresent();
        }
        Optional<Message> existing = existingReply(runId);
        if (existing.isPresent()) {
            return true;
        }
        try {
            Message message = new Message(chatId, "assistant", reply);
            message.setRunId(runId);
            Message saved = messageRepository.saveAndFlush(message);
            touchChat(chatId);
            logger.info("Persisted final reply for run {} in chat {} ({} characters)", runId, chatId, reply.length());
            return saved.getId() != null;
        } catch (DataIntegrityViolationException exception) {
            // A duplicate delivery may race after Redis replay. The unique run_id invariant makes
            // this harmless and the already committed reply remains the canonical one.
            return existingReply(runId).isPresent();
        }
    }

    @Transactional
    public void persistFailureReply(String runId, Long chatId, String detail) {
        if (chatId == null || messageRepository == null || existingReply(runId).isPresent()) {
            return;
        }
        String safeDetail = detail == null || detail.isBlank() ? "Ocorreu uma falha interna." : detail.trim();
        Message message = new Message(
                chatId,
                "assistant",
                "Não consegui concluir esta execução.\n\n> ❌ **Motivo:** " + safeDetail);
        message.setRunId(runId);
        try {
            messageRepository.saveAndFlush(message);
            touchChat(chatId);
        } catch (DataIntegrityViolationException ignored) {
            // Another terminal path saved the same run first.
        }
    }

    public Optional<Message> findReply(String runId) {
        return existingReply(runId);
    }

    private Optional<Message> existingReply(String runId) {
        if (messageRepository == null || runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        return messageRepository.findByRunId(runId);
    }

    private String takeReply(String runId) {
        StringBuilder collected = textByRun.remove(runId);
        return collected == null ? "" : collected.toString().trim();
    }

    private void touchChat(Long chatId) {
        if (chatRepository != null) {
            chatRepository.findById(chatId).ifPresent(chat -> {
                chat.touch();
                chatRepository.save(chat);
            });
        }
    }

    private String visibleContent(String chunk) {
        try {
            JsonNode root = mapper.readTree(chunk);
            if (root.has("avento_event")) {
                return "";
            }
            return root.path("choices").path(0).path("delta").path("content").asText("");
        } catch (Exception exception) {
            logger.debug("Could not read a visible reply chunk", exception);
            return "";
        }
    }
}
