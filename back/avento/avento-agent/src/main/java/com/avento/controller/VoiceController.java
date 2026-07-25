package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.BaseResponse;
import com.avento.api.exception.ApiServiceException;
import com.avento.api.exception.InvalidRequestException;
import com.avento.service.SpeechTextNormalizer;
import com.avento.service.VoiceTranscriptionService;
import com.avento.service.support.PiperCommand;
import com.avento.service.support.ProjectPaths;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoiceController.class);

    private final StringRedisTemplate redisTemplate;
    private final VoiceTranscriptionService voiceTranscriptionService;
    private final SpeechTextNormalizer speechTextNormalizer;

    public VoiceController(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            VoiceTranscriptionService voiceTranscriptionService,
            SpeechTextNormalizer speechTextNormalizer) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.voiceTranscriptionService = voiceTranscriptionService;
        this.speechTextNormalizer = speechTextNormalizer;
    }

    @Value("${avento.voice.piper-binary:}")
    private String piperBinary;

    @Value("${avento.voice.piper-model:}")
    private String piperModel;

    @Value("${avento.voice.piper-model-pt:}")
    private String piperModelPt;

    @Value("${avento.voice.piper-model-en:}")
    private String piperModelEn;

    @Value("${avento.voice.piper-model-es:}")
    private String piperModelEs;

    @Value("${avento.voice.tts-cache-enabled:true}")
    private boolean ttsCacheEnabled;

    @Value("${avento.voice.tts-cache-ttl:PT6H}")
    private Duration ttsCacheTtl;

    @Value("${avento.voice.piper-length-scale:0.95}")
    private double piperLengthScale;

    @Value("${avento.voice.piper-noise-scale:0.60}")
    private double piperNoiseScale;

    @Value("${avento.voice.piper-noise-width-scale:0.80}")
    private double piperNoiseWidthScale;

    @Value("${avento.voice.piper-sentence-silence:0.18}")
    private double piperSentenceSilence;

    @PostMapping("/transcribe")
    public ResponseEntity<BaseResponse<String>> transcribe(
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(value = "preferredLanguage", required = false) String preferredLanguage) {
        if (audio.isEmpty()) {
            throw new InvalidRequestException("Áudio vazio.");
        }

        try {
            return ApiResponses.ok(voiceTranscriptionService.transcribeWebm(audio.getBytes(), preferredLanguage));
        } catch (Exception e) {
            LOGGER.error("Voice transcription failed: {}", e.getMessage());
            throw new ApiServiceException("Não foi possível transcrever o áudio.", e);
        }
    }

    @PostMapping("/tts")
    public ResponseEntity<byte[]> textToSpeech(@RequestBody Map<String, String> payload) {
        String text = payload.get("text");
        if (text == null || text.trim().isEmpty()) {
            throw new InvalidRequestException("Texto para síntese de voz é obrigatório.");
        }

        try {
            String normalizedText = speechTextNormalizer.normalize(text);
            if (normalizedText.isBlank()) {
                throw new InvalidRequestException("O texto não contém conteúdo pronunciável.");
            }
            String requestedLanguage = normalizeLanguage(payload.get("language"));
            String piperScript = ProjectPaths.resolve(piperBinary, "piper_tts", ".venv", "bin", "piper")
                    .toString();
            String resolvedPiperModel = resolvePiperModel(requestedLanguage);
            requirePiperFiles(piperScript, resolvedPiperModel);
            String cacheKey = ttsCacheKey(resolvedPiperModel, requestedLanguage, normalizedText);
            byte[] cachedAudio = readCachedTts(cacheKey);
            if (cachedAudio != null) {
                return wavResponse(cachedAudio);
            }

            Path tempDir = ProjectPaths.projectRoot().resolve("tmp");
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
            }

            File tempWav = File.createTempFile("tts_", ".wav", tempDir.toFile());

            try {
                ProcessBuilder pb = new ProcessBuilder(PiperCommand.create(
                        Path.of(piperScript),
                        Path.of(resolvedPiperModel),
                        tempWav.toPath(),
                        piperLengthScale,
                        piperNoiseScale,
                        piperNoiseWidthScale,
                        piperSentenceSilence));
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);

                Process process = pb.start();
                process.getOutputStream().write(normalizedText.getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().close();

                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    LOGGER.error("Piper TTS exited with code {}", exitCode);
                    throw new ApiServiceException("O sintetizador de voz terminou com erro.", null);
                }

                byte[] audioBytes = Files.readAllBytes(tempWav.toPath());
                writeCachedTts(cacheKey, audioBytes);
                return wavResponse(audioBytes);
            } finally {
                Files.deleteIfExists(tempWav.toPath());
            }

        } catch (Exception e) {
            if (e instanceof InvalidRequestException invalidRequestException) {
                throw invalidRequestException;
            }
            if (e instanceof ApiServiceException apiServiceException) {
                throw apiServiceException;
            }
            LOGGER.error("Piper TTS failed: {}", e.getMessage());
            throw new ApiServiceException("Não foi possível sintetizar a voz.", e);
        }
    }

    private String resolvePiperModel(String language) {
        String languageModel =
                switch (language == null ? "" : language) {
                    case "en" -> piperModelEn;
                    case "es" -> piperModelEs;
                    case "pt" -> piperModelPt;
                    default -> "";
                };

        if (languageModel != null && !languageModel.isBlank()) {
            return ProjectPaths.resolve(languageModel).toString();
        }

        return ProjectPaths.resolve(piperModel, "piper_tts", "pt_BR-faber-medium.onnx")
                .toString();
    }

    private void requirePiperFiles(String piperScript, String resolvedPiperModel) {
        Path binary = Path.of(piperScript);
        if (!Files.isExecutable(binary)) {
            throw new IllegalStateException("Piper binary not found or not executable at " + binary);
        }
        Path model = Path.of(resolvedPiperModel);
        if (!Files.isRegularFile(model)) {
            throw new IllegalStateException("Piper model not found at " + model);
        }
    }

    private String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int separator = normalized.indexOf('-');
        if (separator > 0) {
            normalized = normalized.substring(0, separator);
        }
        return normalized.matches("[a-z]{2}") ? normalized : null;
    }

    private byte[] readCachedTts(String cacheKey) {
        if (!ttsCacheEnabled || redisTemplate == null) {
            return null;
        }

        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached == null || cached.isBlank()) {
                return null;
            }
            return Base64.getDecoder().decode(cached);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeCachedTts(String cacheKey, byte[] audioBytes) {
        if (!ttsCacheEnabled || redisTemplate == null || audioBytes == null || audioBytes.length == 0) {
            return;
        }

        try {
            String encoded = Base64.getEncoder().encodeToString(audioBytes);
            redisTemplate.opsForValue().set(cacheKey, encoded, ttsCacheTtl);
        } catch (Exception ignored) {
            // TTS must keep working even when Redis is unavailable.
        }
    }

    private String ttsCacheKey(String resolvedPiperModel, String language, String text) {
        return "avento:voice:tts:"
                + sha256(resolvedPiperModel + "\n" + (language == null ? "" : language) + "\n"
                        + piperLengthScale + "\n" + piperNoiseScale + "\n" + piperNoiseWidthScale + "\n"
                        + piperSentenceSilence + "\n" + text);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private ResponseEntity<byte[]> wavResponse(byte[] audioBytes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("audio/wav"));
        return new ResponseEntity<>(audioBytes, headers, HttpStatus.OK);
    }
}
