package com.avento.service;

import com.avento.config.VoiceProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SpeechSynthesisService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpeechSynthesisService.class);

    private final StringRedisTemplate redisTemplate;
    private final SpeechTextNormalizer normalizer;
    private final NeuralSpeechSynthesisService neuralSpeech;
    private final PiperSpeechSynthesisService piperSpeech;
    private final VoiceProperties voiceProperties;

    public SpeechSynthesisService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            SpeechTextNormalizer normalizer,
            NeuralSpeechSynthesisService neuralSpeech,
            PiperSpeechSynthesisService piperSpeech,
            VoiceProperties voiceProperties) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.normalizer = normalizer;
        this.neuralSpeech = neuralSpeech;
        this.piperSpeech = piperSpeech;
        this.voiceProperties = voiceProperties;
    }

    public byte[] synthesize(String text, String language, String requestedVoice) throws Exception {
        String normalizedText = normalizer.normalize(text);
        if (normalizedText.isBlank()) {
            throw new IllegalArgumentException("The text does not contain speakable content");
        }
        String normalizedLanguage = normalizeLanguage(language);
        if ("piper".equalsIgnoreCase(voiceProperties.getProvider())) {
            return synthesizeWithPiper(normalizedText, normalizedLanguage);
        }
        try {
            return synthesizeCached(
                    neuralSpeech.cacheIdentity(normalizedLanguage, requestedVoice),
                    normalizedLanguage,
                    normalizedText,
                    () -> neuralSpeech.synthesize(normalizedText, normalizedLanguage, requestedVoice));
        } catch (NeuralSpeechUnavailableException exception) {
            if (!voiceProperties.getNeural().isFallbackToPiper()) {
                throw exception;
            }
            LOGGER.warn("Neural TTS is unavailable; using the local Piper fallback: {}", exception.getMessage());
            return synthesizeWithPiper(normalizedText, normalizedLanguage);
        }
    }

    private byte[] synthesizeWithPiper(String text, String language) throws Exception {
        return synthesizeCached(
                piperSpeech.cacheIdentity(language), language, text, () -> piperSpeech.synthesize(text, language));
    }

    private byte[] synthesizeCached(String engine, String language, String text, AudioSupplier supplier)
            throws Exception {
        String cacheKey = cacheKey(engine, language, text);
        byte[] cached = readCache(cacheKey);
        if (cached != null) return cached;
        byte[] audio = supplier.get();
        writeCache(cacheKey, audio);
        return audio;
    }

    private byte[] readCache(String key) {
        if (!voiceProperties.isTtsCacheEnabled() || redisTemplate == null) return null;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            return cached == null || cached.isBlank()
                    ? null
                    : Base64.getDecoder().decode(cached);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeCache(String key, byte[] audio) {
        if (!voiceProperties.isTtsCacheEnabled() || redisTemplate == null || audio == null || audio.length == 0) return;
        try {
            redisTemplate
                    .opsForValue()
                    .set(key, Base64.getEncoder().encodeToString(audio), voiceProperties.getTtsCacheTtl());
        } catch (Exception ignored) {
            // Cache is optional; a Redis outage must not silence the assistant.
        }
    }

    private String cacheKey(String engine, String language, String text) {
        return "avento:voice:tts:" + sha256(engine + "\n" + (language == null ? "" : language) + "\n" + text);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) return null;
        String normalized = language.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return normalized.matches("[a-z]{2}(-[a-z]{2})?") ? normalized.split("-", 2)[0] : null;
    }

    @FunctionalInterface
    private interface AudioSupplier {
        byte[] get() throws Exception;
    }
}
