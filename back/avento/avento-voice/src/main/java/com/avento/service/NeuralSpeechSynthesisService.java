package com.avento.service;

import com.avento.config.VoiceProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class NeuralSpeechSynthesisService {

    private final VoiceProperties voiceProperties;
    private final HttpClient httpClient;

    @Autowired
    public NeuralSpeechSynthesisService(VoiceProperties voiceProperties) {
        this(
                voiceProperties,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    }

    NeuralSpeechSynthesisService(VoiceProperties voiceProperties, HttpClient httpClient) {
        this.voiceProperties = voiceProperties;
        this.httpClient = httpClient;
    }

    public byte[] synthesize(String text, String language) throws Exception {
        VoiceProperties.Neural neural = voiceProperties.getNeural();
        if (!neural.isEnabled() || neural.getUrl().isBlank()) {
            throw new NeuralSpeechUnavailableException("Neural speech is disabled");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(neural.getUrl() + "/tts"))
                .header("Content-Type", "application/json")
                .timeout(neural.getTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload(text, voiceFor(language))))
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length == 0) {
            throw new NeuralSpeechUnavailableException("Neural speech returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    public String cacheIdentity(String language) {
        return "kokoro:" + voiceFor(language);
    }

    private String voiceFor(String language) {
        return "en".equals(normalizeLanguage(language))
                ? voiceProperties.getNeural().getVoiceEn()
                : voiceProperties.getNeural().getVoicePt();
    }

    private String normalizeLanguage(String language) {
        if (language == null) return "";
        return language.trim().toLowerCase(Locale.ROOT).split("-", 2)[0];
    }

    private String jsonPayload(String text, String voice) {
        return "{\"text\":\"" + escapeJson(text) + "\",\"voice\":\"" + escapeJson(voice) + "\"}";
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
