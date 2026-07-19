package com.avento.service;

import com.avento.service.dto.TranscriptionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

@Component
public class StreamingVoiceWebSocketHandler extends AbstractWebSocketHandler {

    private static final int MAX_UTTERANCE_BYTES = 25 * 1024 * 1024;

    private final ObjectMapper mapper;
    private final VoiceTranscriptionService voiceTranscriptionService;
    private final Map<String, ByteArrayOutputStream> audioBuffers = new ConcurrentHashMap<>();
    private final ExecutorService transcriptionExecutor = Executors.newCachedThreadPool();

    public StreamingVoiceWebSocketHandler(ObjectMapper mapper, VoiceTranscriptionService voiceTranscriptionService) {
        this.mapper = mapper;
        this.voiceTranscriptionService = voiceTranscriptionService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        audioBuffers.put(session.getId(), new ByteArrayOutputStream());
        sendEvent(session, "ready", "Voice WebSocket ready");
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        ByteArrayOutputStream buffer =
                audioBuffers.computeIfAbsent(session.getId(), ignored -> new ByteArrayOutputStream());
        if (buffer.size() + message.getPayloadLength() > MAX_UTTERANCE_BYTES) {
            buffer.reset();
            sendError(session, "Fala muito longa. Tente frases menores.");
            return;
        }
        ByteBuffer payload = message.getPayload();
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        buffer.write(bytes);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode payload = mapper.readTree(message.getPayload());
        String type = payload.path("type").asText();

        if ("reset".equals(type)) {
            audioBuffers
                    .computeIfAbsent(session.getId(), ignored -> new ByteArrayOutputStream())
                    .reset();
            sendEvent(session, "reset", "Buffer reset");
            return;
        }

        if ("flush".equals(type)) {
            String preferredLanguage = payload.path("preferredLanguage").asText(null);
            ByteArrayOutputStream buffer =
                    audioBuffers.computeIfAbsent(session.getId(), ignored -> new ByteArrayOutputStream());
            byte[] audioBytes = buffer.toByteArray();
            buffer.reset();

            if (audioBytes.length == 0) {
                return;
            }

            sendEvent(session, "transcribing", "Transcribing utterance");
            transcriptionExecutor.submit(() -> transcribeAndSend(session, audioBytes, preferredLanguage));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        audioBuffers.remove(session.getId());
    }

    private void transcribeAndSend(WebSocketSession session, byte[] audioBytes, String preferredLanguage) {
        try {
            TranscriptionResult transcript =
                    voiceTranscriptionService.transcribeWebmDetailed(audioBytes, preferredLanguage);
            if (!transcript.text().isBlank()) {
                ObjectNode event = mapper.createObjectNode();
                event.put("type", "transcript.final");
                event.put("text", transcript.text());
                if (transcript.detectedLanguage() != null
                        && !transcript.detectedLanguage().isBlank()) {
                    event.put("language", transcript.detectedLanguage());
                }
                sendText(session, event.toString());
            }
        } catch (Exception e) {
            sendError(session, "Erro ao transcrever áudio: " + e.getMessage());
        }
    }

    private void sendEvent(WebSocketSession session, String type, String detail) throws Exception {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", type);
        event.put("detail", detail);
        sendText(session, event.toString());
    }

    private void sendError(WebSocketSession session, String message) {
        try {
            ObjectNode event = mapper.createObjectNode();
            event.put("type", "error");
            event.put("message", message);
            sendText(session, event.toString());
        } catch (Exception ignored) {
            // The client may already be disconnected.
        }
    }

    private void sendText(WebSocketSession session, String text) throws Exception {
        if (session.isOpen()) {
            synchronized (session) {
                session.sendMessage(new TextMessage(text));
            }
        }
    }
}
