package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.BaseResponse;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    // Assistente local de usuário único: uma chave global de settings é suficiente aqui.
    private static final String SETTINGS_KEY = "avento:user:settings";
    private static final String TTS_FIELD = "ttsEnabled";

    private final StringRedisTemplate redisTemplate;

    public SettingsController(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    @GetMapping
    public ResponseEntity<BaseResponse<Map<String, Boolean>>> getSettings() {
        return ApiResponses.ok(Map.of(TTS_FIELD, readTtsEnabled()));
    }

    @PutMapping
    public ResponseEntity<BaseResponse<Map<String, Boolean>>> updateSettings(@RequestBody Map<String, Boolean> body) {
        Boolean requested = body.get(TTS_FIELD);
        if (requested != null && redisTemplate != null) {
            redisTemplate.opsForHash().put(SETTINGS_KEY, TTS_FIELD, requested.toString());
        }
        return ApiResponses.ok(Map.of(TTS_FIELD, readTtsEnabled()));
    }

    private boolean readTtsEnabled() {
        if (redisTemplate == null) {
            return false;
        }
        Object raw = redisTemplate.opsForHash().get(SETTINGS_KEY, TTS_FIELD);
        return raw != null && "true".equals(raw.toString());
    }
}
