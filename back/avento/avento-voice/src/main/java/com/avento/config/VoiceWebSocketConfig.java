package com.avento.config;

import com.avento.service.StreamingVoiceWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class VoiceWebSocketConfig implements WebSocketConfigurer {

    private static final int MAX_VOICE_BINARY_MESSAGE_BYTES = 26 * 1024 * 1024;
    private static final int MAX_VOICE_TEXT_MESSAGE_BYTES = 64 * 1024;

    private final StreamingVoiceWebSocketHandler streamingVoiceWebSocketHandler;

    public VoiceWebSocketConfig(StreamingVoiceWebSocketHandler streamingVoiceWebSocketHandler) {
        this.streamingVoiceWebSocketHandler = streamingVoiceWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(streamingVoiceWebSocketHandler, "/ws/voice")
                .setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*", "http://[::1]:*");
    }

    @Bean
    public ServletServerContainerFactoryBean voiceWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(MAX_VOICE_BINARY_MESSAGE_BYTES);
        container.setMaxTextMessageBufferSize(MAX_VOICE_TEXT_MESSAGE_BYTES);
        return container;
    }
}
