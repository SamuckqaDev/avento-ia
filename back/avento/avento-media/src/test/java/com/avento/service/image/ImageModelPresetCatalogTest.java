package com.avento.service.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageModelPresetCatalogTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void selectsTheSdxlPresetForRealVisCheckpoints() {
        ImageModelPresetCatalog catalog = new ImageModelPresetCatalog(mapper, "");

        ImageModelPreset preset = catalog.forModel("comfyui:RealVisXL_V5.0_fp16.safetensors");

        assertThat(preset.name()).isEqualTo("sdxl-photoreal");
        assertThat(preset.steps("quality")).isEqualTo(32);
        assertThat(preset.cfg("quality")).isEqualTo(4.8);
        assertThat(preset.sampler()).isEqualTo("dpmpp_2m");
        assertThat(preset.hasNativeDimensions()).isTrue();
        assertThat(preset.dimensions("quality", "portrait")).containsExactly(768, 1024);
    }

    @Test
    void selectsTheFluxPresetWithItsLowCfg() {
        ImageModelPresetCatalog catalog = new ImageModelPresetCatalog(mapper, "");

        ImageModelPreset preset = catalog.forModel("flux-2-klein-4b-fp8.safetensors");

        assertThat(preset.name()).isEqualTo("flux2-klein");
        assertThat(preset.steps("balanced")).isEqualTo(4);
        assertThat(preset.cfg("balanced")).isEqualTo(1.0);
        assertThat(preset.sampler()).isEqualTo("euler");
        assertThat(preset.usesNaturalPrompt()).isTrue();
    }

    @Test
    void fluxBaseVariantGetsItsOwnPresetInsteadOfTheDistilledOne() {
        ImageModelPresetCatalog catalog = new ImageModelPresetCatalog(mapper, "");

        ImageModelPreset preset = catalog.forModel("flux-2-klein-base-4b-fp8.safetensors");

        assertThat(preset.name()).isEqualTo("flux2-klein-base");
        assertThat(preset.steps("balanced")).isEqualTo(20);
        assertThat(preset.cfg("balanced")).isEqualTo(4.0);
        assertThat(preset.usesNaturalPrompt()).isTrue();
    }

    @Test
    void tagBasedPresetsDoNotUseNaturalPrompting() {
        ImageModelPresetCatalog catalog = new ImageModelPresetCatalog(mapper, "");

        assertThat(catalog.forModel("comfyui:RealVisXL_V5.0_fp16.safetensors").usesNaturalPrompt())
                .isFalse();
        assertThat(catalog.forModel("dreamshaper_8.safetensors").usesNaturalPrompt())
                .isFalse();
    }

    @Test
    void fallsBackToTheStableDiffusionPresetForUnknownCheckpoints() {
        ImageModelPresetCatalog catalog = new ImageModelPresetCatalog(mapper, "");

        ImageModelPreset preset = catalog.forModel("dreamshaper_8.safetensors");

        assertThat(preset.name()).isEqualTo("stable-diffusion-1.5");
        assertThat(preset.steps("balanced")).isEqualTo(20);
        assertThat(preset.hasNativeDimensions()).isFalse();
    }

    @Test
    void localPresetsOverrideBundledOnes(@TempDir Path tempDir) throws Exception {
        Path localFile = tempDir.resolve("image-presets.json");
        Files.writeString(localFile, """
                {"presets": [{"name": "meu-realvis", "match": ["realvisxl"],
                  "sampler": "euler_ancestral", "scheduler": "normal",
                  "steps": {"balanced": 40}, "cfg": {"balanced": 3.5},
                  "longEdge": {"balanced": 1152}}]}
                """);
        ImageModelPresetCatalog catalog = new ImageModelPresetCatalog(mapper, localFile.toString());

        ImageModelPreset preset = catalog.forModel("comfyui:RealVisXL_V5.0_fp16.safetensors");

        assertThat(preset.name()).isEqualTo("meu-realvis");
        assertThat(preset.steps("balanced")).isEqualTo(40);
        assertThat(preset.steps("quality")).isEqualTo(40);
        assertThat(preset.cfg("balanced")).isEqualTo(3.5);
        assertThat(preset.sampler()).isEqualTo("euler_ancestral");
        assertThat(preset.dimensions("balanced", "square")).containsExactly(1152, 1152);
    }

    @Test
    void invalidLocalFileFallsBackToBundledPresets(@TempDir Path tempDir) throws Exception {
        Path localFile = tempDir.resolve("image-presets.json");
        Files.writeString(localFile, "{ isso nao é json valido");
        ImageModelPresetCatalog catalog = new ImageModelPresetCatalog(mapper, localFile.toString());

        ImageModelPreset preset = catalog.forModel("comfyui:RealVisXL_V5.0_fp16.safetensors");

        assertThat(preset.name()).isEqualTo("sdxl-photoreal");
    }

    @Test
    void localFileEditsApplyWithoutRestart(@TempDir Path tempDir) throws Exception {
        Path localFile = tempDir.resolve("image-presets.json");
        ImageModelPresetCatalog catalog = new ImageModelPresetCatalog(mapper, localFile.toString());
        assertThat(catalog.forModel("realvisxl.safetensors").name()).isEqualTo("sdxl-photoreal");

        Files.writeString(localFile, "{\"presets\": [{\"name\": \"ajustado\", \"match\": [\"realvisxl\"]}]}");

        assertThat(catalog.forModel("realvisxl.safetensors").name()).isEqualTo("ajustado");
    }
}
