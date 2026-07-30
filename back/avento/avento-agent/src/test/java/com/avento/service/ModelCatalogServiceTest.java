package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.service.dto.LocalModelInfo;
import com.avento.service.provider.ModelProviderService;
import com.avento.service.provider.ProviderKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Com o Gemini ativo, o seletor continuava listando os modelos do Ollama: a interface chama
 * {@code /api/ai/models/details}, que consultava o Ollama direto e não conhecia provedor nenhum. O
 * {@code /api/ollama/models} já tratava nuvem, mas ninguém o chamava.
 *
 * <p>O efeito para quem usa é escolher um nome que não tem efeito nenhum e concluir — com razão —
 * que a seleção de provedor foi ignorada.
 */
class ModelCatalogServiceTest {

    private static final UUID USER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObjectNode geminiListing() {
        ObjectNode root = MAPPER.createObjectNode();
        var data = root.putArray("data");
        data.addObject().put("id", "gemini-2.5-flash").put("name", "Google Gemini 2.5 Flash (Cloud)");
        data.addObject().put("id", "gemini-2.5-pro").put("name", "Google Gemini 2.5 Pro (Cloud)");
        return root;
    }

    @Test
    void listsCloudModelsWhenACloudProviderIsActive() {
        ModelProviderService provider = mock(ModelProviderService.class);
        when(provider.cloudProviderSelected(USER_ID)).thenReturn(true);
        when(provider.activeKind(USER_ID)).thenReturn(ProviderKind.GEMINI);
        when(provider.listAvailableModels(USER_ID)).thenReturn(geminiListing());
        when(provider.cloudModelName(USER_ID)).thenReturn("gemini-2.5-flash");

        List<LocalModelInfo> models = cloudModelsFor(provider, USER_ID);

        assertThat(models).extracting(LocalModelInfo::name).containsExactly("gemini-2.5-flash", "gemini-2.5-pro");
    }

    // O modelo configurado tem de vir marcado, senao a UI elege outro como "recomendado".
    @Test
    void marksTheConfiguredModelAsRecommended() {
        ModelProviderService provider = mock(ModelProviderService.class);
        when(provider.cloudProviderSelected(USER_ID)).thenReturn(true);
        when(provider.activeKind(USER_ID)).thenReturn(ProviderKind.GEMINI);
        when(provider.listAvailableModels(USER_ID)).thenReturn(geminiListing());
        when(provider.cloudModelName(USER_ID)).thenReturn("gemini-2.5-pro");

        List<LocalModelInfo> models = cloudModelsFor(provider, USER_ID);

        assertThat(models.stream().filter(LocalModelInfo::recommended))
                .extracting(LocalModelInfo::name)
                .containsExactly("gemini-2.5-pro");
    }

    // Marcado como vision: senao anexar imagem faria a UI trocar para um modelo local de visao.
    @Test
    void marksCloudModelsAsVisionCapable() {
        ModelProviderService provider = mock(ModelProviderService.class);
        when(provider.cloudProviderSelected(USER_ID)).thenReturn(true);
        when(provider.activeKind(USER_ID)).thenReturn(ProviderKind.GEMINI);
        when(provider.listAvailableModels(USER_ID)).thenReturn(geminiListing());
        when(provider.cloudModelName(USER_ID)).thenReturn("gemini-2.5-flash");

        assertThat(cloudModelsFor(provider, USER_ID)).allMatch(LocalModelInfo::vision);
    }

    // family carrega o TIPO: e por ele que a interface mostra de onde a resposta vem.
    @Test
    void tagsModelsWithTheProviderKind() {
        ModelProviderService provider = mock(ModelProviderService.class);
        when(provider.cloudProviderSelected(USER_ID)).thenReturn(true);
        when(provider.activeKind(USER_ID)).thenReturn(ProviderKind.GEMINI);
        when(provider.listAvailableModels(USER_ID)).thenReturn(geminiListing());
        when(provider.cloudModelName(USER_ID)).thenReturn("gemini-2.5-flash");

        assertThat(cloudModelsFor(provider, USER_ID)).allMatch(model -> "GEMINI".equals(model.family()));
    }

    @Test
    void fallsBackToLocalListingWhenNoCloudProviderIsActive() {
        ModelProviderService provider = mock(ModelProviderService.class);
        when(provider.cloudProviderSelected(USER_ID)).thenReturn(false);

        assertThat(cloudModelsFor(provider, USER_ID)).isEmpty();
    }

    @Test
    void fallsBackToLocalListingWithoutTheProviderService() {
        assertThat(cloudModelsFor(null, USER_ID)).isEmpty();
    }

    private List<LocalModelInfo> cloudModelsFor(ModelProviderService provider, UUID userId) {
        ModelCatalogService service = new ModelCatalogService(
                "http://localhost:11434", "granite4.1:8b", "qwen2.5vl:7b", "comfyui:modelo.safetensors");
        service.setModelProviderService(provider);
        return service.cloudModelsFor(userId);
    }

    // A UI usa a flag preferredForVision para nao trocar de modelo sozinha quando o usuario anexa
    // imagem. Era um teste por reflexao no AgentService; agora chama o servico que de fato lista.
    @Test
    void marksTheConfiguredVisionModelForTheFrontend() {
        ObjectNode tags = MAPPER.createObjectNode();
        ObjectNode model = tags.putArray("models").addObject();
        model.put("name", "qwen2.5vl:7b");
        model.putObject("details").put("family", "qwen25vl").put("parameter_size", "8.3B");

        ModelCatalogService service = new ModelCatalogService(
                "http://localhost:11434", "granite4.1:8b", "qwen2.5vl:7b", "comfyui:modelo.safetensors");
        List<LocalModelInfo> models = service.parseOllamaTags(tags);

        assertThat(models).hasSize(1);
        assertThat(models.get(0).vision()).isTrue();
        assertThat(models.get(0).preferredForVision()).isTrue();
    }
}
