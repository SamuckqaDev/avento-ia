package com.avento.service;

import com.avento.config.VoiceProperties;
import com.avento.service.support.PiperCommand;
import com.avento.service.support.ProjectPaths;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class PiperSpeechSynthesisService {

    private final VoiceProperties voiceProperties;

    public PiperSpeechSynthesisService(VoiceProperties voiceProperties) {
        this.voiceProperties = voiceProperties;
    }

    public byte[] synthesize(String text, String language) throws Exception {
        String piperScript = ProjectPaths.resolve(
                        voiceProperties.getPiperBinary(), "piper_tts", ".venv", "bin", "piper")
                .toString();
        String model = resolveModel(language);
        requireFiles(piperScript, model);
        Path temporaryDirectory = ProjectPaths.projectRoot().resolve("tmp");
        Files.createDirectories(temporaryDirectory);
        File output = File.createTempFile("tts_", ".wav", temporaryDirectory.toFile());
        try {
            Process process = new ProcessBuilder(PiperCommand.create(
                            Path.of(piperScript),
                            Path.of(model),
                            output.toPath(),
                            voiceProperties.getPiperLengthScale(),
                            voiceProperties.getPiperNoiseScale(),
                            voiceProperties.getPiperNoiseWidthScale(),
                            voiceProperties.getPiperSentenceSilence()))
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            process.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            if (process.waitFor() != 0) {
                throw new IllegalStateException("Piper TTS terminated with an error");
            }
            return Files.readAllBytes(output.toPath());
        } finally {
            Files.deleteIfExists(output.toPath());
        }
    }

    public String cacheIdentity(String language) {
        return resolveModel(language) + ":" + voiceProperties.getPiperLengthScale() + ":"
                + voiceProperties.getPiperNoiseScale() + ":" + voiceProperties.getPiperNoiseWidthScale() + ":"
                + voiceProperties.getPiperSentenceSilence();
    }

    private String resolveModel(String language) {
        String model =
                switch (normalizeLanguage(language)) {
                    case "en" -> voiceProperties.getPiperModelEn();
                    case "es" -> voiceProperties.getPiperModelEs();
                    case "pt" -> voiceProperties.getPiperModelPt();
                    default -> "";
                };
        return ProjectPaths.resolve(model == null || model.isBlank() ? voiceProperties.getPiperModel() : model)
                .toString();
    }

    private String normalizeLanguage(String language) {
        if (language == null) return "";
        return language.trim().toLowerCase(Locale.ROOT).split("-", 2)[0];
    }

    private void requireFiles(String script, String model) {
        if (!Files.isExecutable(Path.of(script))) {
            throw new IllegalStateException("Piper binary not found or not executable at " + script);
        }
        if (!Files.isRegularFile(Path.of(model))) {
            throw new IllegalStateException("Piper model not found at " + model);
        }
    }
}
