package com.avento.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "avento.voice")
public class VoiceProperties {

    private String provider = "kokoro";
    private boolean ttsCacheEnabled = true;
    private Duration ttsCacheTtl = Duration.ofHours(6);
    private String piperBinary = "";
    private String piperModel = "./piper_tts/pt_BR-faber-medium.onnx";
    private String piperModelPt = "";
    private String piperModelEn = "";
    private String piperModelEs = "";
    private double piperLengthScale = 1.0;
    private double piperNoiseScale = 0.45;
    private double piperNoiseWidthScale = 0.65;
    private double piperSentenceSilence = 0.24;
    private Neural neural = new Neural();

    @Getter
    @Setter
    public static class Neural {
        private boolean enabled = true;
        private String url = "http://127.0.0.1:8880";
        private String voicePt = "pf_dora";
        private String voiceEn = "af_heart";
        private boolean fallbackToPiper = true;
        private Duration timeout = Duration.ofSeconds(90);
    }
}
