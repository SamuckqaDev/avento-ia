package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.service.provider.ModelProviderService;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Escolher Gemini na tela não muda nada: o fluxo de conversa monta corpo no formato nativo do Ollama
 * e chama {@code /api/chat} num WebClient com a base URL fixada no construtor. Os métodos
 * {@code resolveActiveModelUrl}/{@code resolveActiveModelName} existem no ModelProviderService e
 * nunca foram chamados por ninguém.
 *
 * <p>Até a camada de provedor existir, o mínimo honesto é dizer — senão o usuário julga a qualidade
 * do Gemini olhando para a saída de um modelo local de 9B.
 */
class AgentServiceCloudNoticeTest {

    private static final UUID USER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Test
    void warnsWhenACloudProviderIsSelected() throws Exception {
        ModelProviderService provider = mock(ModelProviderService.class);
        when(provider.cloudProviderSelected(USER_ID)).thenReturn(true);
        when(provider.selectedCloudProviderName(USER_ID)).thenReturn("GEMINI (gemini-2.5-flash)");

        String notice = serviceWith(provider).cloudProviderNotice(USER_ID, "qwen3.5:9b");

        assertThat(notice).contains("GEMINI (gemini-2.5-flash)");
        assertThat(notice).contains("qwen3.5:9b");
        assertThat(notice).contains("não da nuvem");
    }

    @Test
    void staysSilentWhenNoCloudProviderIsSelected() throws Exception {
        ModelProviderService provider = mock(ModelProviderService.class);
        when(provider.cloudProviderSelected(USER_ID)).thenReturn(false);

        assertThat(serviceWith(provider).cloudProviderNotice(USER_ID, "qwen3.5:9b"))
                .isEmpty();
    }

    // Sem o servico injetado (testes que montam o AgentService a mao), nao avisa nada.
    @Test
    void staysSilentWithoutTheProviderService() throws Exception {
        assertThat(serviceWith(null).cloudProviderNotice(USER_ID, "qwen3.5:9b")).isEmpty();
    }

    @Test
    void staysSilentForAnonymousUser() throws Exception {
        ModelProviderService provider = mock(ModelProviderService.class);
        when(provider.cloudProviderSelected(null)).thenReturn(false);

        assertThat(serviceWith(provider).cloudProviderNotice(null, "qwen3.5:9b"))
                .isEmpty();
    }

    /** Instancia sem construtor: o metodo sob teste usa apenas o campo injetado. */
    private AgentService serviceWith(ModelProviderService provider) throws Exception {
        AgentService service = (AgentService) newInstanceWithoutConstructor();
        Field field = AgentService.class.getDeclaredField("modelProviderService");
        field.setAccessible(true);
        field.set(service, provider);
        return service;
    }

    private Object newInstanceWithoutConstructor() throws Exception {
        return org.mockito.Mockito.mock(
                AgentService.class,
                org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
    }
}
