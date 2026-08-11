package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.config.VoiceProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class SpeechSynthesisServiceTest {

    @Test
    void fallsBackToPiperWhenTheNeuralRuntimeIsUnavailable() throws Exception {
        VoiceProperties properties = new VoiceProperties();
        properties.setTtsCacheEnabled(false);
        NeuralSpeechSynthesisService neural = Mockito.mock(NeuralSpeechSynthesisService.class);
        PiperSpeechSynthesisService piper = Mockito.mock(PiperSpeechSynthesisService.class);
        when(neural.cacheIdentity("pt")).thenReturn("kokoro:pf_dora");
        when(neural.synthesize("Olá Avento.", "pt")).thenThrow(new NeuralSpeechUnavailableException("offline"));
        when(piper.cacheIdentity("pt")).thenReturn("piper:pt");
        when(piper.synthesize("Olá Avento.", "pt")).thenReturn(new byte[] {1, 2, 3});

        byte[] audio = service(properties, neural, piper).synthesize("Olá **Avento**.", "pt-BR");

        assertThat(audio).containsExactly(1, 2, 3);
        verify(piper).synthesize("Olá Avento.", "pt");
    }

    @SuppressWarnings("unchecked")
    private SpeechSynthesisService service(
            VoiceProperties properties, NeuralSpeechSynthesisService neural, PiperSpeechSynthesisService piper) {
        ObjectProvider<StringRedisTemplate> redis = Mockito.mock(ObjectProvider.class);
        return new SpeechSynthesisService(redis, new SpeechTextNormalizer(), neural, piper, properties);
    }
}
