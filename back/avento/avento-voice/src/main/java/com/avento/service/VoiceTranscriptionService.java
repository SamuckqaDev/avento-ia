package com.avento.service;

import com.avento.service.dto.*;
import com.avento.service.support.ProjectPaths;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VoiceTranscriptionService {

    private static final int WHISPER_TIMEOUT_SECONDS = 30;
    private static final Pattern DETECTED_LANGUAGE_PATTERN =
            Pattern.compile("auto-detected language:\\s*([a-z]{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern CYRILLIC_PATTERN = Pattern.compile("\\p{IsCyrillic}");
    private static final Set<String> PORTUGUESE_MARKERS = Set.of(
            "bom",
            "dia",
            "otimo",
            "ótimo",
            "seguinte",
            "projeto",
            "analisa",
            "analisar",
            "passos",
            "etapas",
            "preciso",
            "voce",
            "você",
            "quero",
            "falei",
            "portugues",
            "português",
            "transcreva",
            "cara",
            "mano",
            "pra",
            "para",
            "isso",
            "aqui",
            "meu",
            "minha",
            "como",
            "que",
            "nao",
            "não",
            "sim");
    private static final Set<String> ENGLISH_MARKERS = Set.of(
            "i", "need", "you", "to", "analyze", "project", "steps", "the", "please", "can", "could", "would", "what",
            "how", "this", "that", "is", "are", "do");
    public static final Set<String> INVALID_TRANSCRIPT_PLACEHOLDERS =
            Set.of("speaking in foreign language", "foreign language", "non english speech", "inaudible", "music");
    private static final String DEFAULT_WHISPER_PROMPT = String.join(
            " ",
            "Avento. Portugues brasileiro natural.",
            "Transcreva exatamente o que foi dito, incluindo conversas casuais.",
            "Nao acrescente nomes de aplicativos, como Finder, Terminal, Brave ou VS Code,",
            "a menos que eles tenham sido claramente falados.");

    @Value("${avento.voice.ffmpeg-path:/opt/homebrew/bin/ffmpeg}")
    private String ffmpegPath;

    @Value("${avento.voice.whisper-binary:}")
    private String whisperBinary;

    @Value("${avento.voice.whisper-model:}")
    private String whisperModel;

    @Value("${avento.voice.whisper-language:auto}")
    private String whisperLanguage;

    @Value("${avento.voice.preferred-language:pt}")
    private String preferredWhisperLanguage;

    @Value("${avento.voice.allowed-languages:pt,en,es}")
    private String allowedLanguages;

    @Value("${avento.voice.whisper-prompt:}")
    private String whisperPrompt;

    @Value("${avento.voice.whisper-carry-initial-prompt:true}")
    private boolean whisperCarryInitialPrompt;

    @Value("${avento.voice.whisper-beam-size:5}")
    private int whisperBeamSize;

    @Value("${avento.voice.whisper-best-of:5}")
    private int whisperBestOf;

    @Value("${avento.voice.whisper-vad-enabled:true}")
    private boolean whisperVadEnabled;

    @Value("${avento.voice.whisper-vad-model:back/whisper.cpp/models/ggml-silero-v6.2.0.bin}")
    private String whisperVadModel;

    @Value("${avento.voice.whisper-vad-threshold:0.50}")
    private double whisperVadThreshold;

    @Value("${avento.voice.whisper-vad-min-silence-ms:550}")
    private int whisperVadMinSilenceMs;

    @Value("${avento.voice.whisper-vad-speech-pad-ms:120}")
    private int whisperVadSpeechPadMs;

    public VoiceTranscriptionService() {}

    public String transcribeWebm(byte[] audioBytes, String preferredLanguage) throws Exception {
        return transcribeWebmDetailed(audioBytes, preferredLanguage).text();
    }

    public TranscriptionResult transcribeWebmDetailed(byte[] audioBytes, String preferredLanguage) throws Exception {
        if (audioBytes == null || audioBytes.length == 0) {
            return new TranscriptionResult("", normalizeLanguage(preferredLanguage));
        }

        return transcribeWithLocalWhisper(audioBytes, preferredLanguage);
    }

    private TranscriptionResult transcribeWithLocalWhisper(byte[] audioBytes, String preferredLanguage)
            throws Exception {
        Path tempDir = ProjectPaths.projectRoot().resolve("tmp");
        Files.createDirectories(tempDir);

        String uniqueId = UUID.randomUUID().toString();
        File tempInput = tempDir.resolve(uniqueId + ".webm").toFile();
        File tempOutput = tempDir.resolve(uniqueId + ".wav").toFile();

        Files.write(tempInput.toPath(), audioBytes);
        try {
            convertWebmToWav(tempInput, tempOutput);
            return transcribeWav(tempOutput, preferredLanguage);
        } finally {
            Files.deleteIfExists(tempInput.toPath());
            Files.deleteIfExists(tempOutput.toPath());
        }
    }

    private void convertWebmToWav(File tempInput, File tempOutput) throws Exception {
        ProcessBuilder ffmpegPb = new ProcessBuilder(
                ffmpegPath,
                "-y",
                "-i",
                tempInput.getAbsolutePath(),
                "-ar",
                "16000",
                "-ac",
                "1",
                "-c:a",
                "pcm_s16le",
                tempOutput.getAbsolutePath());
        ffmpegPb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        ffmpegPb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process ffmpegProcess = ffmpegPb.start();
        boolean ffmpegDone = ffmpegProcess.waitFor(15, TimeUnit.SECONDS);
        if (!ffmpegDone) {
            ffmpegProcess.destroyForcibly();
            throw new IllegalStateException("FFmpeg excedeu 15 s ao converter o audio.");
        }
        if (ffmpegProcess.exitValue() != 0 || !tempOutput.exists()) {
            throw new IllegalStateException(
                    "FFmpeg nao conseguiu converter o audio (exit " + ffmpegProcess.exitValue() + ").");
        }
    }

    private TranscriptionResult transcribeWav(File wavFile, String clientPreferredLanguage) throws Exception {
        String configuredLanguage = languageFlag();
        TranscriptionResult primary = runWhisper(wavFile, configuredLanguage);
        String preferredLanguage = preferredLanguage(clientPreferredLanguage);

        if (shouldRetryDisallowedLanguage(configuredLanguage, primary, preferredLanguage)) {
            return runPreferredOrFail(wavFile, preferredLanguage);
        }

        if (shouldRetryUnexpectedScript(configuredLanguage, primary, preferredLanguage)) {
            return runPreferredOrFail(wavFile, preferredLanguage);
        }

        if (hasUnexpectedScript(primary.text(), configuredLanguage)) {
            throw new IllegalStateException(
                    "Whisper retornou texto em alfabeto inesperado para portugues. Tente repetir mais perto do microfone.");
        }

        if (!shouldRetryWithPreferredLanguage(configuredLanguage, primary, preferredLanguage)) {
            return primary;
        }

        TranscriptionResult preferred = runWhisper(wavFile, preferredLanguage);
        return shouldPreferTranscription(preferred.text(), primary.text(), preferredLanguage)
                ? preferred
                : primary.withoutPlaceholder();
    }

    private TranscriptionResult runPreferredOrFail(File wavFile, String preferredLanguage) throws Exception {
        if (preferredLanguage == null || preferredLanguage.isBlank()) {
            throw new IllegalStateException("Whisper nao conseguiu identificar um idioma permitido para a fala.");
        }

        TranscriptionResult preferred = runWhisper(wavFile, preferredLanguage);
        if (hasUnexpectedScript(preferred.text(), preferredLanguage)) {
            throw new IllegalStateException("Whisper retornou texto em alfabeto inesperado para " + preferredLanguage
                    + ". Tente repetir mais perto do microfone.");
        }
        return preferred.withoutPlaceholder();
    }

    private TranscriptionResult runWhisper(File wavFile, String language) throws Exception {
        Path whisperExecutable =
                ProjectPaths.resolve(whisperBinary, "back", "whisper.cpp", "build", "bin", "whisper-cli");
        String modelPath = ProjectPaths.resolve(whisperModel, "back", "whisper.cpp", "models", "ggml-base.bin")
                .toString();

        List<String> whisperCmd = new ArrayList<>();
        whisperCmd.add(whisperExecutable.toString());
        whisperCmd.add("-m");
        whisperCmd.add(modelPath);
        whisperCmd.add("-f");
        whisperCmd.add(wavFile.getAbsolutePath());
        whisperCmd.add("-nt");
        whisperCmd.add("-bs");
        whisperCmd.add(Integer.toString(Math.max(1, whisperBeamSize)));
        whisperCmd.add("-bo");
        whisperCmd.add(Integer.toString(Math.max(1, whisperBestOf)));
        String prompt = safeWhisperPrompt();
        if (!prompt.isBlank()) {
            whisperCmd.add("--prompt");
            whisperCmd.add(prompt);
            if (whisperCarryInitialPrompt) {
                whisperCmd.add("--carry-initial-prompt");
            }
        }
        if (language != null && !language.isBlank()) {
            whisperCmd.add("-l");
            whisperCmd.add(language);
        }
        appendVadOptions(whisperCmd);

        ProcessBuilder whisperPb = new ProcessBuilder(whisperCmd);
        configureWhisperRuntime(whisperPb, whisperExecutable);
        whisperPb.redirectErrorStream(false);
        Process whisperProcess = whisperPb.start();

        CompletableFuture<String> outputFuture = readProcessOutput(whisperProcess.getInputStream(), " ");
        CompletableFuture<String> errorFuture = readProcessOutput(whisperProcess.getErrorStream(), "\n");

        boolean done = whisperProcess.waitFor(WHISPER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!done) {
            whisperProcess.destroyForcibly();
            throw new IllegalStateException(
                    "Whisper excedeu " + WHISPER_TIMEOUT_SECONDS + " s. Verifique o modelo ou o hardware.");
        }

        String output = outputFuture.get(2, TimeUnit.SECONDS);
        String errOutput = errorFuture.get(2, TimeUnit.SECONDS);
        if (whisperProcess.exitValue() != 0) {
            String err = errOutput.trim();
            throw new IllegalStateException("Whisper falhou (exit " + whisperProcess.exitValue() + ")"
                    + (err.isEmpty() ? "." : ": " + err.lines().findFirst().orElse(err)));
        }

        String transcript = cleanTranscript(output);
        if (isWhisperPromptEcho(transcript)) {
            transcript = "";
        }
        String detectedLanguage = detectedLanguage(errOutput);
        String normalizedRequestedLanguage = normalizeLanguage(language);
        String finalLanguage = detectedLanguage != null ? detectedLanguage : normalizedRequestedLanguage;
        return new TranscriptionResult(transcript, finalLanguage);
    }

    static void configureWhisperRuntime(ProcessBuilder processBuilder, Path whisperExecutable) {
        Path libraryDirectory = whisperExecutable.toAbsolutePath().normalize().getParent();
        if (libraryDirectory == null) {
            return;
        }

        prependEnvironmentPath(processBuilder, "DYLD_LIBRARY_PATH", libraryDirectory);
        prependEnvironmentPath(processBuilder, "LD_LIBRARY_PATH", libraryDirectory);
    }

    private void appendVadOptions(List<String> command) {
        if (!whisperVadEnabled || whisperVadModel == null || whisperVadModel.isBlank()) {
            return;
        }
        Path model = ProjectPaths.resolve(whisperVadModel);
        if (!Files.isRegularFile(model)) {
            return;
        }
        command.add("--vad");
        command.add("--vad-model");
        command.add(model.toString());
        command.add("--vad-threshold");
        command.add(Double.toString(Math.max(0.05, Math.min(0.95, whisperVadThreshold))));
        command.add("--vad-min-silence-duration-ms");
        command.add(Integer.toString(Math.max(100, whisperVadMinSilenceMs)));
        command.add("--vad-speech-pad-ms");
        command.add(Integer.toString(Math.max(0, whisperVadSpeechPadMs)));
    }

    private static void prependEnvironmentPath(ProcessBuilder processBuilder, String variable, Path directory) {
        String currentValue = processBuilder.environment().get(variable);
        String directoryValue = directory.toString();
        processBuilder
                .environment()
                .put(
                        variable,
                        currentValue == null || currentValue.isBlank()
                                ? directoryValue
                                : directoryValue + File.pathSeparator + currentValue);
    }

    private String safeWhisperPrompt() {
        if (whisperPrompt == null || whisperPrompt.isBlank()) {
            return DEFAULT_WHISPER_PROMPT;
        }
        return whisperPrompt.trim();
    }

    private CompletableFuture<String> readProcessOutput(InputStream stream, String separator) {
        return CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(separator);
                }
                return output.toString();
            } catch (Exception exception) {
                throw new IllegalStateException("Erro ao ler saida do processo.", exception);
            }
        });
    }

    private String languageFlag() {
        if (whisperLanguage == null || whisperLanguage.isBlank()) {
            return "auto";
        }
        return whisperLanguage.trim().toLowerCase(Locale.ROOT);
    }

    private String preferredLanguage(String clientPreferredLanguage) {
        String normalized = normalizeLanguage(clientPreferredLanguage);
        if (normalized != null) {
            return normalized;
        }
        return normalizeLanguage(preferredWhisperLanguage);
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

    private boolean shouldRetryWithPreferredLanguage(
            String configuredLanguage, TranscriptionResult primary, String preferredLanguage) {
        return "auto".equals(configuredLanguage)
                && preferredLanguage != null
                && !"en".equals(preferredLanguage)
                && ("en".equals(primary.detectedLanguage()) || primary.placeholder())
                && !primary.text().isBlank();
    }

    private boolean shouldRetryDisallowedLanguage(
            String configuredLanguage, TranscriptionResult primary, String preferredLanguage) {
        return "auto".equals(configuredLanguage)
                && preferredLanguage != null
                && primary.detectedLanguage() != null
                && !isAllowedLanguage(primary.detectedLanguage());
    }

    private boolean shouldRetryUnexpectedScript(
            String configuredLanguage, TranscriptionResult primary, String preferredLanguage) {
        return hasUnexpectedScript(primary.text(), configuredLanguage)
                && preferredLanguage != null
                && !preferredLanguage.equals(configuredLanguage);
    }

    boolean hasUnexpectedScript(String text, String expectedLanguage) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalizedLanguage = normalizeLanguage(expectedLanguage);
        return ("pt".equals(normalizedLanguage) || "en".equals(normalizedLanguage) || "es".equals(normalizedLanguage))
                && CYRILLIC_PATTERN.matcher(text).find();
    }

    boolean isAllowedLanguage(String language) {
        String normalized = normalizeLanguage(language);
        if (normalized == null) {
            return false;
        }
        String configuredAllowedLanguages =
                allowedLanguages == null || allowedLanguages.isBlank() ? "pt,en,es" : allowedLanguages;
        for (String allowedLanguage : configuredAllowedLanguages.split(",")) {
            if (normalized.equals(normalizeLanguage(allowedLanguage))) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldPreferTranscription(String candidate, String original, String preferredLanguage) {
        if (isInvalidTranscriptPlaceholder(original)) {
            return !isInvalidTranscriptPlaceholder(candidate) && candidate != null && !candidate.isBlank();
        }

        if (!"pt".equals(preferredLanguage) || candidate == null || candidate.isBlank()) {
            return false;
        }

        int candidatePortugueseScore = languageScore(candidate, PORTUGUESE_MARKERS);
        int candidateEnglishScore = languageScore(candidate, ENGLISH_MARKERS);
        int originalPortugueseScore = languageScore(original, PORTUGUESE_MARKERS);
        int originalEnglishScore = languageScore(original, ENGLISH_MARKERS);

        return (candidatePortugueseScore >= 2 && candidatePortugueseScore >= candidateEnglishScore)
                || (originalEnglishScore >= 2 && candidatePortugueseScore > originalPortugueseScore);
    }

    private int languageScore(String text, Set<String> markers) {
        String[] words = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .trim()
                .split("\\s+");
        int score = 0;
        for (String word : words) {
            if (markers.contains(word)) {
                score += 1;
            }
        }
        return score;
    }

    private String cleanTranscript(String output) {
        return output.trim().replaceAll("\\[.*?\\]", "").trim();
    }

    private boolean isInvalidTranscriptPlaceholder(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[()\\[\\].,!?:;\"']", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return INVALID_TRANSCRIPT_PLACEHOLDERS.contains(normalized);
    }

    /**
     * Whisper.cpp with --carry-initial-prompt occasionally echoes fragments of its own
     * initial prompt back as the "transcript" on short/quiet/ambiguous audio instead of
     * transcribing real speech (e.g. "O que foi dito?" or "Portugues brasileiro natural.").
     * Rather than hardcoding every phrase from the default prompt, this checks whether the
     * transcript is itself a verbatim run of words lifted from the configured prompt, which
     * also keeps working if the prompt is customized via avento.voice.whisper-prompt.
     */
    boolean isWhisperPromptEcho(String text) {
        String normalizedText = normalizeForPromptComparison(text);
        if (normalizedText.isBlank()) {
            return false;
        }
        if (normalizedText.split("\\s+").length < 3) {
            return false;
        }
        String normalizedPrompt = normalizeForPromptComparison(safeWhisperPrompt());
        return normalizedPrompt.contains(normalizedText);
    }

    private String normalizeForPromptComparison(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String detectedLanguage(String errorOutput) {
        Matcher matcher = DETECTED_LANGUAGE_PATTERN.matcher(errorOutput == null ? "" : errorOutput);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }
}
