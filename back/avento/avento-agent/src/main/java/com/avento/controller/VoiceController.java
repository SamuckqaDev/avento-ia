package com.avento.controller;

import com.avento.dto.BaseResponse;
import com.avento.dto.api.ApiResponses;
import com.avento.model.exception.ApiServiceException;
import com.avento.model.exception.InvalidRequestException;
import com.avento.service.SpeechSynthesisService;
import com.avento.service.VoiceTranscriptionService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final VoiceTranscriptionService voiceTranscriptionService;
    private final SpeechSynthesisService speechSynthesisService;

    public VoiceController(
            VoiceTranscriptionService voiceTranscriptionService, SpeechSynthesisService speechSynthesisService) {
        this.voiceTranscriptionService = voiceTranscriptionService;
        this.speechSynthesisService = speechSynthesisService;
    }

    @PostMapping("/transcribe")
    public ResponseEntity<BaseResponse<String>> transcribe(
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(value = "preferredLanguage", required = false) String preferredLanguage) {
        if (audio.isEmpty()) {
            throw new InvalidRequestException("Áudio vazio.");
        }
        try {
            return ApiResponses.ok(voiceTranscriptionService.transcribeWebm(audio.getBytes(), preferredLanguage));
        } catch (Exception exception) {
            LOGGER.error("Voice transcription failed: {}", exception.getMessage());
            throw new ApiServiceException("Não foi possível transcrever o áudio.", exception);
        }
    }

    @PostMapping("/tts")
    public ResponseEntity<byte[]> textToSpeech(@RequestBody Map<String, String> payload) {
        String text = payload.get("text");
        if (text == null || text.isBlank()) {
            throw new InvalidRequestException("Texto para síntese de voz é obrigatório.");
        }
        try {
            return wavResponse(speechSynthesisService.synthesize(text, payload.get("language"), payload.get("voice")));
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException("O texto não contém conteúdo pronunciável.");
        } catch (Exception exception) {
            LOGGER.error("Voice synthesis failed: {}", exception.getMessage());
            throw new ApiServiceException("Não foi possível sintetizar a voz.", exception);
        }
    }

    private ResponseEntity<byte[]> wavResponse(byte[] audioBytes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("audio/wav"));
        return new ResponseEntity<>(audioBytes, headers, HttpStatus.OK);
    }
}
