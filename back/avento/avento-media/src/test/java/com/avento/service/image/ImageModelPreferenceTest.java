package com.avento.service.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.service.ComfyUiImageService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * O campo "modelo de imagem" da tela era gravado no banco e nunca lido — {@code activeImageModel}
 * não tinha um único chamador. Toda geração caía no {@code avento.image.default-model} do arquivo,
 * então escolher outro modelo na interface não mudava nada e nada avisava.
 */
class ImageModelPreferenceTest {

    private static final String YAML_DEFAULT = "comfyui:RealVisXL_V5.0_fp16.safetensors";

    @Test
    void usaOModeloEscolhidoNasConfiguracoesQuandoAChamadaNaoPedeUm() {
        ImageGenerationService service = serviceWithConfigured("comfyui:Flux_dev.safetensors");

        assertThat(service.resolveModel(Map.of("prompt", "um gato"))).isEqualTo("comfyui:Flux_dev.safetensors");
    }

    @Test
    void oModeloPedidoNaChamadaGanhaDaConfiguracao() {
        ImageGenerationService service = serviceWithConfigured("comfyui:Flux_dev.safetensors");

        assertThat(service.resolveModel(Map.of("prompt", "um gato", "model", "comfyui:Outro.safetensors")))
                .isEqualTo("comfyui:Outro.safetensors");
    }

    @Test
    void semEscolhaGravadaContinuaValendoOModeloDoArquivo() {
        ImageGenerationService service = serviceWithConfigured(null);

        assertThat(service.resolveModel(Map.of("prompt", "um gato"))).isEqualTo(YAML_DEFAULT);
    }

    @Test
    void modeloDiretoNaoERedirecionadoParaComfyUiQuandoEleEoPadraoGlobal() {
        ComfyUiImageService comfy = mock(ComfyUiImageService.class);
        ImagePromptTranslator translator = mock(ImagePromptTranslator.class);
        when(translator.toEnglish("um gato")).thenReturn("a cat");
        ImageGenerationService service = new ImageGenerationService(comfy, translator, new ObjectMapper(), mock(ObjectProvider.class));
        ReflectionTestUtils.setField(service, "ollamaBaseUrl", "http://127.0.0.1:1");

        service.generate(Map.of("prompt", "um gato", "model", "direct:imagem-local:latest"));

        verify(comfy, never()).generateImage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @SuppressWarnings("unchecked")
    private ImageGenerationService serviceWithConfigured(String configuredModel) {
        ConfiguredImageModel configured = mock(ConfiguredImageModel.class);
        when(configured.preferred()).thenReturn(Optional.ofNullable(configuredModel));
        ObjectProvider<ConfiguredImageModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(configured);

        ImageGenerationService service = new ImageGenerationService(
                mock(ComfyUiImageService.class), mock(ImagePromptTranslator.class), new ObjectMapper(), provider);
        // O @Value não roda fora do contexto do Spring; o padrão do YAML entra na mão.
        ReflectionTestUtils.setField(service, "defaultImageModel", YAML_DEFAULT);
        return service;
    }
}
