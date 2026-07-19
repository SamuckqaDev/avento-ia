package com.avento.service.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImageGenerationOptionsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultsToCompositionWithVisualValidation() {
        ImageGenerationOptions options = ImageGenerationOptions.defaults();

        assertThat(options.referenceMode()).isEqualTo("composition");
        assertThat(options.structureControl()).isEqualTo("depth");
        assertThat(options.structureStrength()).isEqualTo(0.75);
        assertThat(options.adherenceValidationEnabled()).isTrue();
        assertThat(options.maxAdherenceRetries()).isEqualTo(1);
    }

    @Test
    void readsIdentityModeFromJson() throws Exception {
        ImageGenerationOptions options = ImageGenerationOptions.from(mapper.readTree("""
                {
                  "referenceImageDataUrl": "data:image/png;base64,AA==",
                  "referenceMode": "identity",
                  "structureControl": "none",
                  "structureStrength": 1.2,
                  "adherenceValidationEnabled": false,
                  "maxAdherenceRetries": 2
                }
                """));

        assertThat(options.usesIdentityReference()).isTrue();
        assertThat(options.usesImg2ImgReference()).isFalse();
        assertThat(options.usesStructureReference()).isFalse();
        assertThat(options.adherenceValidationEnabled()).isFalse();
        assertThat(options.maxAdherenceRetries()).isEqualTo(2);
    }

    @Test
    void clampsControlsAndNormalizesUnknownModesFromMap() {
        ImageGenerationOptions options = ImageGenerationOptions.from(Map.of(
                "referenceImageDataUrl", "data:image/png;base64,AA==",
                "referenceMode", "unknown",
                "structureControl", "unknown",
                "structureStrength", 8,
                "maxAdherenceRetries", 9));

        assertThat(options.referenceMode()).isEqualTo("composition");
        assertThat(options.structureControl()).isEqualTo("depth");
        assertThat(options.structureStrength()).isEqualTo(1.5);
        assertThat(options.maxAdherenceRetries()).isEqualTo(2);
        assertThat(options.usesStructureReference()).isTrue();
    }
}
