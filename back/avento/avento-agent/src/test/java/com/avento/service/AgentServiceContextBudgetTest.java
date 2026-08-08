package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.service.provider.ModelProviderService;
import com.avento.service.provider.ProviderKind;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * O contexto efetivo era decidido por "é remoto?", partindo de que remoto significa nuvem paga, que
 * aloca a janela anunciada. Um Ollama noutra máquina da rede é remoto e não é nuvem nenhuma.
 *
 * <p>O {@code qwen3.5:35b} declara 262144 tokens no {@code /api/show} e sobe com os 4096 padrão do
 * servidor. Acreditar no declarado quebrava dos dois lados: no caminho nativo o Avento pedia um
 * {@code num_ctx} cujo KV cache passa de 40 GB, e no caminho compatível com OpenAI o pedido era
 * descartado — o protocolo não tem esse campo — e o Ollama cortava o prompt calado, de 5394 tokens
 * para 2050. O modelo perdia a pergunta e respondia com uma saudação.
 */
class AgentServiceContextBudgetTest {

    private static final UUID USER = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final int CONFIGURED = 32_768;

    @Test
    void respeitaAJanelaQueAInstanciaCarregouDeFato() throws Exception {
        ModelProviderService provider = selfHosted(262_144, 4_096);

        assertThat(serviceWith(provider).effectiveContextTokens(USER)).isEqualTo(4_096);
    }

    /** Sem saber o carregado, o teto configurado ainda tem de vencer o declarado pelo modelo. */
    @Test
    void nuncaConfiaNoTetoDoModeloNumServidorAutoHospedado() throws Exception {
        ModelProviderService provider = selfHosted(262_144, 0);

        assertThat(serviceWith(provider).effectiveContextTokens(USER)).isEqualTo(CONFIGURED);
    }

    /** Modelo de janela pequena continua mandando: não adianta pedir mais do que ele aguenta. */
    @Test
    void oDeclaradoAindaLimitaQuandoEMenorQueOConfigurado() throws Exception {
        ModelProviderService provider = selfHosted(8_192, 0);

        assertThat(serviceWith(provider).effectiveContextTokens(USER)).isEqualTo(8_192);
    }

    /** Num serviço gerenciado quem aloca a janela é o provedor, então o declarado vale inteiro. */
    @Test
    void serviçoGerenciadoUsaAJanelaQueAnuncia() throws Exception {
        ModelProviderService provider = mock(ModelProviderService.class);
        when(provider.activeContextLimit(USER)).thenReturn(1_048_576);
        when(provider.effectiveKind(USER)).thenReturn(ProviderKind.GEMINI);

        assertThat(serviceWith(provider).effectiveContextTokens(USER)).isEqualTo(1_048_576);
    }

    /** Provedor que não sabe informar nada deixa o configurado decidir. */
    @Test
    void caiNoConfiguradoQuandoNinguemDeclaraNada() throws Exception {
        ModelProviderService provider = mock(ModelProviderService.class);
        when(provider.activeContextLimit(USER)).thenReturn(0);

        assertThat(serviceWith(provider).effectiveContextTokens(USER)).isEqualTo(CONFIGURED);
    }

    private ModelProviderService selfHosted(int declared, int loaded) {
        ModelProviderService provider = mock(ModelProviderService.class);
        when(provider.activeContextLimit(USER)).thenReturn(declared);
        when(provider.activeLoadedContextLimit(USER)).thenReturn(loaded);
        when(provider.effectiveKind(USER)).thenReturn(ProviderKind.OPENAI_COMPATIBLE);
        // Espelha a configuracao real que expos o defeito: a Fedora e um provedor REMOTO e pronto,
        // e era exatamente isso que fazia o codigo antigo entregar o teto do modelo.
        when(provider.remoteProviderReady(USER)).thenReturn(true);
        return provider;
    }

    /** Instancia sem construtor: o metodo sob teste usa apenas os dois campos injetados. */
    private AgentService serviceWith(ModelProviderService provider) throws Exception {
        AgentService service = mock(
                AgentService.class,
                org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
        set(service, "modelProviderService", provider);
        set(service, "numCtx", CONFIGURED);
        return service;
    }

    private void set(AgentService service, String name, Object value) throws Exception {
        Field field = AgentService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    /**
     * A armadilha que se realimenta: onde o Avento PODE pedir a janela, esse numero vira o
     * {@code num_ctx} da requisicao. Limita-lo pelo que ja estava carregado faria ele ler 4096,
     * pedir 4096, e nunca mais sair da janela pequena em que caiu uma vez.
     */
    @Test
    void naoSePrendeNaJanelaPequenaQuandoPodePedirOutra() throws Exception {
        ModelProviderService provider = mock(ModelProviderService.class);
        when(provider.activeContextLimit(USER)).thenReturn(262_144);
        when(provider.activeLoadedContextLimit(USER)).thenReturn(4_096);
        when(provider.effectiveKind(USER)).thenReturn(ProviderKind.OLLAMA);

        assertThat(serviceWith(provider).effectiveContextTokens(USER)).isEqualTo(CONFIGURED);
    }
}
