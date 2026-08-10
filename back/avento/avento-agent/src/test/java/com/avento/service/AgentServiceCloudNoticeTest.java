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
        when(provider.remoteProviderReady(USER_ID)).thenReturn(true);
        when(provider.selectedCloudProviderName(USER_ID)).thenReturn("GEMINI (gemini-2.5-flash)");

        String notice = serviceWith(provider).cloudProviderNotice(USER_ID, "qwen3.5:9b");

        assertThat(notice).contains("GEMINI (gemini-2.5-flash)");
        assertThat(notice).contains("qwen3.5:9b");
        assertThat(notice).contains("rodando localmente");
    }

    @Test
    void staysSilentWhenNoCloudProviderIsSelected() throws Exception {
        ModelProviderService provider = mock(ModelProviderService.class);
        when(provider.remoteProviderReady(USER_ID)).thenReturn(false);

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
        when(provider.remoteProviderReady(null)).thenReturn(false);

        assertThat(serviceWith(provider).cloudProviderNotice(null, "qwen3.5:9b"))
                .isEmpty();
    }

    /** Instancia sem construtor: o metodo sob teste usa apenas o campo injetado. */
    private AgentService serviceWith(ModelProviderService provider) throws Exception {
        AgentService service = (AgentService) newInstanceWithoutConstructor();
        Field field = AgentService.class.getDeclaredField("modelProviderService");
        field.setAccessible(true);
        field.set(service, provider);
        // contentChunk serializa com o mapper; sem ele a instancia montada por reflexao estoura.
        Field mapperField = AgentService.class.getDeclaredField("mapper");
        mapperField.setAccessible(true);
        mapperField.set(service, new tools.jackson.databind.ObjectMapper());
        return service;
    }

    private Object newInstanceWithoutConstructor() throws Exception {
        return org.mockito.Mockito.mock(
                AgentService.class,
                org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
    }

    /**
     * Regressao vista na tela: assim que a deteccao automatica entrou, o aviso passou a dizer que a
     * resposta "nao veio da nuvem" justamente quando ela vinha. Sem transporte deixou de significar
     * "caiu no modelo local" — com um Ollama no endereco configurado, o caminho nativo atende usando
     * a mesma base URL.
     */
    @Test
    void staysSilentWhenTheNativePathServesTheConfiguredAddress() throws Exception {
        ModelProviderService provider = mock(ModelProviderService.class);
        when(provider.remoteProviderReady(USER_ID)).thenReturn(true);
        when(provider.effectiveKind(USER_ID)).thenReturn(com.avento.service.provider.ProviderKind.OLLAMA);

        assertThat(serviceWith(provider).cloudProviderNotice(USER_ID, "qwen3.5:9b"))
                .isEmpty();
    }

    /** Um provedor de verdade sem transporte continua merecendo o aviso. */
    @Test
    void stillWarnsWhenNoPathServesTheChosenProvider() throws Exception {
        ModelProviderService provider = mock(ModelProviderService.class);
        when(provider.remoteProviderReady(USER_ID)).thenReturn(true);
        when(provider.effectiveKind(USER_ID)).thenReturn(com.avento.service.provider.ProviderKind.GEMINI);
        when(provider.selectedCloudProviderName(USER_ID)).thenReturn("GEMINI (gemini-2.5-flash)");

        assertThat(serviceWith(provider).cloudProviderNotice(USER_ID, "qwen3.5:9b"))
                .contains("não tem transporte disponível");
    }

    /**
     * O aviso nomeia o provedor GRAVADO em Provedores, que nao e o modelo escolhido no seletor do
     * cabecalho. Dizer "voce selecionou" fazia o texto apontar para a escolha errada — a pessoa lia
     * "voce selecionou qwen3.5:35b" logo depois de ter escolhido qwen3.5:9b na conversa.
     */
    @Test
    void namesTheConfiguredProviderNotTheOneChosenInTheHeader() throws Exception {
        ModelProviderService provider = mock(ModelProviderService.class);
        when(provider.remoteProviderReady(USER_ID)).thenReturn(true);
        when(provider.effectiveKind(USER_ID)).thenReturn(com.avento.service.provider.ProviderKind.GEMINI);
        when(provider.selectedCloudProviderName(USER_ID)).thenReturn("GEMINI (gemini-2.5-flash)");

        String notice = serviceWith(provider).cloudProviderNotice(USER_ID, "qwen3.5:9b");

        assertThat(notice).contains("configurado");
        assertThat(notice).doesNotContain("Você selecionou");
    }

    /**
     * O aviso vivia no {@code streamChatResolved}, e o caminho de skill retorna antes de chegar la —
     * entao a mesma configuracao quebrada avisava num "oi" e ficava calada numa busca na web. Agora
     * os dois ramos passam por este metodo, que e o unico lugar onde a colagem acontece.
     */
    @Test
    void prefixesTheNoticeToWhateverTurnIsRunning() throws Exception {
        AgentService service = serviceWith(mock(ModelProviderService.class));

        java.util.List<String> saida = service.withCloudNotice("AVISO", reactor.core.publisher.Flux.just("resposta"))
                .collectList()
                .block();

        assertThat(saida).hasSize(2);
        assertThat(saida.get(0)).contains("AVISO");
        assertThat(saida.get(1)).contains("resposta");
    }

    /** Sem aviso, nada e acrescentado — nem um chunk vazio, que apareceria como linha em branco. */
    @Test
    void leavesTheTurnUntouchedWhenThereIsNoNotice() throws Exception {
        AgentService service = serviceWith(mock(ModelProviderService.class));

        assertThat(service.withCloudNotice("", reactor.core.publisher.Flux.just("resposta"))
                        .collectList()
                        .block())
                .hasSize(1);
    }
}
