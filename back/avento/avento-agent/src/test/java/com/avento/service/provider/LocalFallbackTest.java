package com.avento.service.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.model.ProviderSettings;
import com.avento.model.ProviderSettingsRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * A máquina de inferência desliga, o Tailscale cai, o wifi troca de rede. Aconteceu: o Avento estava
 * de pé, a Fedora saiu do ar, e cada mensagem virava erro de conexão — com um Ollama local vivo ao
 * lado, sem ninguém usar.
 *
 * <p>Cair para o local salva a conversa, mas entrega OUTRO modelo, quase sempre menor. Por isso a
 * queda é anunciada, e o modelo gravado não vai junto: o Mac não tem o {@code qwen3.5:35b} do
 * servidor remoto, e insistir nele daria 404 a cada mensagem.
 */
class LocalFallbackTest {

    private static final UUID USER = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final String LOCAL = "http://127.0.0.1:11434";
    private static final String REMOTO = "http://<tailnet-host>:11434";

    @Test
    void desviaParaOLocalQuandoORemotoNaoResponde() {
        ProviderModelCatalog catalog = mock(ProviderModelCatalog.class);
        when(catalog.isReachable(REMOTO)).thenReturn(false);
        ModelProviderService service = serviceFor("OPENAI_COMPATIBLE", REMOTO, "qwen3.5:35b", catalog, true);

        assertThat(service.fellBackToLocal(USER)).isTrue();
        assertThat(service.activeBaseUrl(USER)).isEqualTo(LOCAL);
        // O endereço gravado não muda: a tela continua mostrando a escolha da pessoa.
        assertThat(service.configuredBaseUrl(USER)).isEqualTo(REMOTO);
    }

    /** O modelo do outro servidor não existe aqui: levá-lo junto trocaria a queda por um 404. */
    @Test
    void naoLevaOModeloDoServidorRemotoParaOLocal() {
        ProviderModelCatalog catalog = mock(ProviderModelCatalog.class);
        when(catalog.isReachable(REMOTO)).thenReturn(false);
        ModelProviderService service = serviceFor("OPENAI_COMPATIBLE", REMOTO, "qwen3.5:35b", catalog, true);

        assertThat(service.activeModelName(USER)).isNotEqualTo("qwen3.5:35b");
    }

    @Test
    void naoDesviaEnquantoORemotoResponde() {
        ProviderModelCatalog catalog = mock(ProviderModelCatalog.class);
        when(catalog.isReachable(REMOTO)).thenReturn(true);
        ModelProviderService service = serviceFor("OPENAI_COMPATIBLE", REMOTO, "qwen3.5:35b", catalog, true);

        assertThat(service.fellBackToLocal(USER)).isFalse();
        assertThat(service.activeBaseUrl(USER)).isEqualTo(REMOTO);
        assertThat(service.activeModelName(USER)).isEqualTo("qwen3.5:35b");
    }

    /**
     * Gemini e Anthropic fora do ar são problema deles. Desviar para um 8B local sem avisar
     * entregaria outra qualidade com a mesma cara — e a chave de API não serve para nada aqui.
     */
    @Test
    void naoDesviaProvedorGerenciado() {
        ProviderModelCatalog catalog = mock(ProviderModelCatalog.class);
        ModelProviderService service =
                serviceFor("GEMINI", "https://generativelanguage.googleapis.com", "gemini-2.5-flash", catalog, true);

        assertThat(service.fellBackToLocal(USER)).isFalse();
        verify(catalog, never()).isReachable(anyString());
    }

    /** Quem prefere ver o erro a receber outro modelo pode desligar. */
    @Test
    void respeitaODesligamentoDoFallback() {
        ProviderModelCatalog catalog = mock(ProviderModelCatalog.class);
        when(catalog.isReachable(REMOTO)).thenReturn(false);
        ModelProviderService service = serviceFor("OPENAI_COMPATIBLE", REMOTO, "qwen3.5:35b", catalog, false);

        assertThat(service.fellBackToLocal(USER)).isFalse();
        assertThat(service.activeBaseUrl(USER)).isEqualTo(REMOTO);
    }

    /** A sondagem não pode entrar no caminho de cada mensagem: uma vez por janela de validade. */
    @Test
    void sondaUmaVezSoDentroDaValidade() {
        ProviderModelCatalog catalog = mock(ProviderModelCatalog.class);
        when(catalog.isReachable(REMOTO)).thenReturn(false);
        ModelProviderService service = serviceFor("OPENAI_COMPATIBLE", REMOTO, "qwen3.5:35b", catalog, true);

        service.fellBackToLocal(USER);
        service.fellBackToLocal(USER);
        service.fellBackToLocal(USER);

        verify(catalog, org.mockito.Mockito.times(1)).isReachable(REMOTO);
    }

    private ModelProviderService serviceFor(
            String kind, String baseUrl, String model, ProviderModelCatalog catalog, boolean fallbackLigado) {
        ProviderSettings settings = new ProviderSettings(USER);
        settings.setProviderKind(kind);
        settings.setBaseUrl(baseUrl);
        settings.setCloudModel(model);

        ProviderSettingsRepository repository = mock(ProviderSettingsRepository.class);
        when(repository.findById(USER)).thenReturn(Optional.of(settings));

        ModelProviderService service = new ModelProviderService(
                providerOf(mock(StringRedisTemplate.class)),
                LOCAL,
                new ObjectMapper(),
                providerOf(repository),
                providerOf(mock(SecretCipher.class)),
                providerOf(catalog),
                fallbackLigado);
        ReflectionTestUtils.setField(service, "modelCatalog", catalog);
        ReflectionTestUtils.setField(service, "repository", repository);
        return service;
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
