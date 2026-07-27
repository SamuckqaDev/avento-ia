package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.service.dto.LocalModelInfo;
import com.avento.service.provider.ModelProviderService;
import com.avento.service.provider.ProviderKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
class AgentServiceCloudModelListTest {

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
    void listsCloudModelsWhenACloudProviderIsActive() throws Exception {
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
    void marksTheConfiguredModelAsRecommended() throws Exception {
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
    void marksCloudModelsAsVisionCapable() throws Exception {
        ModelProviderService provider = mock(ModelProviderService.class);
        when(provider.cloudProviderSelected(USER_ID)).thenReturn(true);
        when(provider.activeKind(USER_ID)).thenReturn(ProviderKind.GEMINI);
        when(provider.listAvailableModels(USER_ID)).thenReturn(geminiListing());
        when(provider.cloudModelName(USER_ID)).thenReturn("gemini-2.5-flash");

        assertThat(cloudModelsFor(provider, USER_ID)).allMatch(LocalModelInfo::vision);
    }

    // family carrega o TIPO: e por ele que a interface mostra de onde a resposta vem.
    @Test
    void tagsModelsWithTheProviderKind() throws Exception {
        ModelProviderService provider = mock(ModelProviderService.class);
        when(provider.cloudProviderSelected(USER_ID)).thenReturn(true);
        when(provider.activeKind(USER_ID)).thenReturn(ProviderKind.GEMINI);
        when(provider.listAvailableModels(USER_ID)).thenReturn(geminiListing());
        when(provider.cloudModelName(USER_ID)).thenReturn("gemini-2.5-flash");

        assertThat(cloudModelsFor(provider, USER_ID)).allMatch(model -> "GEMINI".equals(model.family()));
    }

    @Test
    void fallsBackToLocalListingWhenNoCloudProviderIsActive() throws Exception {
        ModelProviderService provider = mock(ModelProviderService.class);
        when(provider.cloudProviderSelected(USER_ID)).thenReturn(false);

        assertThat(cloudModelsFor(provider, USER_ID)).isEmpty();
    }

    @Test
    void fallsBackToLocalListingWithoutTheProviderService() throws Exception {
        assertThat(cloudModelsFor(null, USER_ID)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private List<LocalModelInfo> cloudModelsFor(ModelProviderService provider, UUID userId) throws Exception {
        AgentService service = mock(
                AgentService.class,
                org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
        Field field = AgentService.class.getDeclaredField("modelProviderService");
        field.setAccessible(true);
        field.set(service, provider);

        Method method = AgentService.class.getDeclaredMethod("cloudModelsFor", UUID.class);
        method.setAccessible(true);
        return (List<LocalModelInfo>) method.invoke(service, userId);
    }
}
