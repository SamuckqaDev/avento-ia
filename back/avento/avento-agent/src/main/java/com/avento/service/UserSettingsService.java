package com.avento.service;

import com.avento.api.dto.UserSettingsRequest;
import com.avento.api.dto.UserSettingsResponse;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class UserSettingsService {

    private static final String KEY_PREFIX = "avento:user:";
    private static final String KEY_SUFFIX = ":settings";
    private static final String TTS_FIELD = "ttsEnabled";
    private static final String THINKING_FIELD = "thinkingEnabled";
    private static final String AUTO_APPROVE_FIELD = "autoApproveAll";

    private final StringRedisTemplate redisTemplate;

    public UserSettingsService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    public UserSettingsResponse get(UUID userId) {
        UUID owner = requireUser(userId);
        return new UserSettingsResponse(
                readFlag(owner, TTS_FIELD, false),
                readFlag(owner, THINKING_FIELD, true),
                readFlag(owner, AUTO_APPROVE_FIELD, true));
    }

    public UserSettingsResponse update(UUID userId, UserSettingsRequest request) {
        UUID owner = requireUser(userId);
        if (redisTemplate != null && request != null) {
            put(owner, TTS_FIELD, request.ttsEnabled());
            put(owner, THINKING_FIELD, request.thinkingEnabled());
            put(owner, AUTO_APPROVE_FIELD, request.autoApproveAll());
        }
        return get(owner);
    }

    public UserSettingsResponse restoreDefaults(UUID userId) {
        UUID owner = requireUser(userId);
        if (redisTemplate != null) {
            redisTemplate.delete(key(owner));
        }
        return get(owner);
    }

    public boolean thinkingEnabled(UUID userId, boolean fallback) {
        if (userId == null) {
            return fallback;
        }
        return readFlag(userId, THINKING_FIELD, fallback);
    }

    public boolean autoApproveAllEnabled(UUID userId, boolean fallback) {
        if (userId == null) {
            return fallback;
        }
        return readFlag(userId, AUTO_APPROVE_FIELD, fallback);
    }

    private void put(UUID userId, String field, Boolean value) {
        if (value != null) {
            redisTemplate.opsForHash().put(key(userId), field, value.toString());
        }
    }

    private boolean readFlag(UUID userId, String field, boolean fallback) {
        if (redisTemplate == null) {
            return fallback;
        }
        try {
            Object raw = redisTemplate.opsForHash().get(key(userId), field);
            return raw == null ? fallback : Boolean.parseBoolean(raw.toString());
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private String key(UUID userId) {
        return KEY_PREFIX + userId + KEY_SUFFIX;
    }

    private UUID requireUser(UUID userId) {
        if (userId == null) {
            throw new SecurityException("Usuário autenticado é obrigatório para acessar configurações.");
        }
        return userId;
    }
}
