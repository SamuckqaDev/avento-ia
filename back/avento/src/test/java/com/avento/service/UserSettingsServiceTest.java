package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.api.dto.UserSettingsRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class UserSettingsServiceTest {

    @Test
    void storesSettingsUnderTheAuthenticatedUsersKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForHash()).thenReturn(hashes);
        UUID userId = UUID.randomUUID();
        UserSettingsService service = new UserSettingsService(provider);

        service.update(userId, new UserSettingsRequest(true, false));

        String key = "avento:user:" + userId + ":settings";
        verify(hashes).put(key, "ttsEnabled", "true");
        verify(hashes).put(key, "thinkingEnabled", "false");
    }

    @Test
    void usesSafeDefaultsWhenRedisIsUnavailable() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        UserSettingsService service = new UserSettingsService(provider);

        assertThat(service.get(UUID.randomUUID()).ttsEnabled()).isFalse();
        assertThat(service.get(UUID.randomUUID()).thinkingEnabled()).isTrue();
    }
}
