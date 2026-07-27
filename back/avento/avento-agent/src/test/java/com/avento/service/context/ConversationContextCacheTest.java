package com.avento.service.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.config.RedisExecutionProperties;
import com.avento.model.Message;
import com.avento.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class ConversationContextCacheTest {

    @Test
    void rebuildsCanonicalContextAndKeepsTheEnrichedLatestRequest() {
        MessageRepository repository = mock(MessageRepository.class);
        Message first = message(1L, "user", "mensagem antiga");
        Message second = message(2L, "assistant", "resposta antiga");
        Message latest = message(3L, "user", "analise o projeto");
        when(repository.findByChatIdOrderByTimestampAsc(42L)).thenReturn(List.of(first, second, latest));

        RedisExecutionProperties properties = new RedisExecutionProperties();
        properties.setEnabled(false);
        ConversationContextCache cache =
                new ConversationContextCache(repository, new ObjectMapper(), emptyRedisProvider(), properties);

        ObjectMapper mapper = new ObjectMapper();
        ArrayNode request = mapper.createArrayNode();
        ObjectNode enriched = request.addObject();
        enriched.put("role", "user");
        enriched.put("content", "[Workspace Roots]\n/Users/test/project\n\nanalise o projeto");
        enriched.putArray("images").add("base64-reference");

        ArrayNode resolved = cache.resolve(UUID.randomUUID(), 42L, request);

        assertThat(resolved).hasSize(3);
        assertThat(resolved.get(0).path("content").asText()).isEqualTo("mensagem antiga");
        assertThat(resolved.get(2).path("content").asText()).contains("[Workspace Roots]");
        assertThat(resolved.get(2).path("images").get(0).asText()).isEqualTo("base64-reference");
        verify(repository).findByChatIdOrderByTimestampAsc(42L);
    }

    @Test
    void limitsTheRebuiltWindowToTheConfiguredNumberOfMessages() {
        MessageRepository repository = mock(MessageRepository.class);
        when(repository.findByChatIdOrderByTimestampAsc(7L))
                .thenReturn(List.of(
                        message(1L, "user", "one"), message(2L, "assistant", "two"), message(3L, "user", "three")));
        RedisExecutionProperties properties = new RedisExecutionProperties();
        properties.setEnabled(false);
        properties.setContextMessageLimit(2);
        ConversationContextCache cache =
                new ConversationContextCache(repository, new ObjectMapper(), emptyRedisProvider(), properties);

        ArrayNode resolved = cache.resolve(UUID.randomUUID(), 7L, new ObjectMapper().createArrayNode());

        assertThat(resolved).hasSize(2);
        assertThat(resolved.get(0).path("content").asText()).isEqualTo("two");
        assertThat(resolved.get(1).path("content").asText()).isEqualTo("three");
    }

    private Message message(Long id, String role, String content) {
        Message message = new Message(42L, role, content);
        message.setId(id);
        return message;
    }

    private ObjectProvider<StringRedisTemplate> emptyRedisProvider() {
        return new StaticListableBeanFactory().getBeanProvider(StringRedisTemplate.class);
    }
}
