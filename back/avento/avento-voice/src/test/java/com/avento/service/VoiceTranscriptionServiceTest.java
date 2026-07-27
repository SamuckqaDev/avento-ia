package com.avento.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VoiceTranscriptionServiceTest {

    private final VoiceTranscriptionService service = new VoiceTranscriptionService();

    @Test
    void detectsCyrillicAsUnexpectedForPortuguese() {
        assertTrue(service.hasUnexpectedScript("А, ночь.", "pt"));
    }

    @Test
    void acceptsLatinTextForPortuguese() {
        assertFalse(service.hasUnexpectedScript("Boa noite.", "pt"));
    }

    @Test
    void doesNotApplyPortugueseScriptGuardToAutoLanguage() {
        assertFalse(service.hasUnexpectedScript("А, ночь.", "auto"));
    }

    @Test
    void allowsExpectedLocalLanguages() {
        assertTrue(service.isAllowedLanguage("pt"));
        assertTrue(service.isAllowedLanguage("en-US"));
        assertTrue(service.isAllowedLanguage("es"));
    }

    @Test
    void rejectsUnexpectedDetectedLanguages() {
        assertFalse(service.isAllowedLanguage("ru"));
    }

    @Test
    void detectsWhisperEchoingItsOwnPrompt() {
        assertTrue(service.isWhisperPromptEcho("O que foi dito?"));
        assertTrue(service.isWhisperPromptEcho("Portugues brasileiro natural."));
    }

    @Test
    void doesNotFlagRealSpeechAsPromptEcho() {
        assertFalse(service.isWhisperPromptEcho("Abre o Brave para mim"));
        assertFalse(service.isWhisperPromptEcho("Boa noite"));
        assertFalse(service.isWhisperPromptEcho("oi"));
    }

    @Test
    void configuresDynamicLibrariesFromTheCurrentWhisperDirectory() {
        Path executable = Path.of("/tmp/avento/whisper/build/bin/whisper-cli");
        ProcessBuilder processBuilder = new ProcessBuilder("true");
        processBuilder.environment().put("DYLD_LIBRARY_PATH", "/existing/dylibs");

        VoiceTranscriptionService.configureWhisperRuntime(processBuilder, executable);

        String expected = "/tmp/avento/whisper/build/bin" + File.pathSeparator + "/existing/dylibs";
        assertEquals(expected, processBuilder.environment().get("DYLD_LIBRARY_PATH"));
        assertEquals(
                "/tmp/avento/whisper/build/bin", processBuilder.environment().get("LD_LIBRARY_PATH"));
    }
}
