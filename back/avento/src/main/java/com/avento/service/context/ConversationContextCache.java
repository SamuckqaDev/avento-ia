package com.avento.service.context;

import com.avento.config.RedisExecutionProperties;
import com.avento.model.Message;
import com.avento.repository.MessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Rebuildable cache of recent canonical chat messages. PostgreSQL remains the source of truth. */
@Service
public class ConversationContextCache {

    private static final Logger logger = LoggerFactory.getLogger(ConversationContextCache.class);

    private final MessageRepository messageRepository;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redisTemplate;
    private final RedisExecutionProperties properties;

    public ConversationContextCache(
            MessageRepository messageRepository,
            ObjectMapper mapper,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            RedisExecutionProperties properties) {
        this.messageRepository = messageRepository;
        this.mapper = mapper;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.properties = properties;
    }

    public ArrayNode resolve(UUID userId, Long chatId, ArrayNode requestMessages) {
        if (chatId == null) {
            return copy(requestMessages);
        }

        ArrayNode canonical = read(userId, chatId);
        if (canonical == null) {
            canonical = rebuild(userId, chatId);
        }
        if (canonical == null || canonical.isEmpty()) {
            return copy(requestMessages);
        }

        JsonNode latestRequest = latestUserMessage(requestMessages);
        if (latestRequest != null) {
            replaceLatestUserMessage(canonical, latestRequest);
        }
        return canonical;
    }

    public void refresh(UUID userId, Long chatId) {
        if (chatId != null) {
            rebuild(userId, chatId);
        }
    }

    public void evict(UUID userId, Long chatId) {
        if (!cacheEnabled() || chatId == null) {
            return;
        }
        try {
            redisTemplate.delete(key(userId, chatId));
        } catch (RuntimeException exception) {
            logger.debug("Could not evict Redis context for chat {}", chatId, exception);
        }
    }

    private ArrayNode rebuild(UUID userId, Long chatId) {
        List<Message> messages = messageRepository.findByChatIdOrderByTimestampAsc(chatId);
        int fromIndex = Math.max(0, messages.size() - properties.getContextMessageLimit());
        ArrayNode context = mapper.createArrayNode();
        for (Message message : messages.subList(fromIndex, messages.size())) {
            context.add(toModelMessage(message));
        }
        write(
                userId,
                chatId,
                context,
                messages.isEmpty() ? null : messages.get(messages.size() - 1).getId());
        return context;
    }

    private ArrayNode read(UUID userId, Long chatId) {
        if (!cacheEnabled()) {
            return null;
        }
        try {
            String value = redisTemplate.opsForValue().get(key(userId, chatId));
            if (value == null || value.isBlank()) {
                return null;
            }
            JsonNode snapshot = mapper.readTree(value);
            JsonNode messages = snapshot.path("messages");
            return messages.isArray() ? ((ArrayNode) messages).deepCopy() : null;
        } catch (Exception exception) {
            logger.debug("Could not read Redis context for chat {}; rebuilding it", chatId, exception);
            return null;
        }
    }

    private void write(UUID userId, Long chatId, ArrayNode messages, Long lastMessageId) {
        if (!cacheEnabled()) {
            return;
        }
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("chatId", chatId);
        snapshot.put("userId", ownerKey(userId));
        if (lastMessageId != null) {
            snapshot.put("lastMessageId", lastMessageId);
        }
        snapshot.put("updatedAt", Instant.now().toString());
        snapshot.set("messages", messages);
        try {
            redisTemplate
                    .opsForValue()
                    .set(key(userId, chatId), mapper.writeValueAsString(snapshot), properties.getContextTtl());
        } catch (Exception exception) {
            logger.debug(
                    "Could not cache context for chat {}; PostgreSQL fallback remains available", chatId, exception);
        }
    }

    private ObjectNode toModelMessage(Message message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", message.getRole() == null ? "user" : message.getRole());
        String content = message.getContent() == null ? "" : message.getContent();
        if (message.getDocumentContext() != null
                && !message.getDocumentContext().isBlank()) {
            content = message.getDocumentContext() + "\n\n[Pedido do usuário]\n" + content;
        }
        node.put("content", content);
        return node;
    }

    private JsonNode latestUserMessage(ArrayNode messages) {
        if (messages == null) {
            return null;
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            JsonNode message = messages.get(index);
            if ("user".equals(message.path("role").asText())) {
                return message;
            }
        }
        return null;
    }

    private void replaceLatestUserMessage(ArrayNode messages, JsonNode replacement) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if ("user".equals(messages.get(index).path("role").asText())) {
                messages.set(index, replacement.deepCopy());
                return;
            }
        }
        messages.add(replacement.deepCopy());
    }

    private ArrayNode copy(ArrayNode messages) {
        return messages == null ? mapper.createArrayNode() : messages.deepCopy();
    }

    private boolean cacheEnabled() {
        return properties.isEnabled() && redisTemplate != null;
    }

    private String key(UUID userId, Long chatId) {
        return "avento:context:" + ownerKey(userId) + ":" + chatId;
    }

    private String ownerKey(UUID userId) {
        return userId == null ? "local" : userId.toString();
    }
}
