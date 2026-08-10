package com.avento.service.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
 * O Ollama fala os dois protocolos — o próprio em {@code /api/*} e o da OpenAI em {@code /v1} — então
 * apontar "compatível com OpenAI" para um Ollama é configuração válida, e era silenciosamente pior.
 *
 * <p>O formato da OpenAI não tem {@code num_ctx}, então o pedido de janela do Avento era descartado
 * no transporte e o servidor subia com os 4096 padrão dele. O prompt de 5970 tokens virava 2050, o
 * modelo perdia a pergunta e inventava a resposta. Nada disso aparecia como erro.
 *
 * <p>Perguntar ao endereço o que ele é custa uma requisição e evita que quem usa o produto precise
 * conhecer a diferença entre dois protocolos para configurá-lo.
 */
class EffectiveProviderKindTest {

    private static final UUID USER = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final String FEDORA = "http://<tailnet-host>:11434";

    @Test
    void promoveParaNativoQuandoOEnderecoRespondeComoOllama() {
        ProviderModelCatalog catalog = mock(ProviderModelCatalog.class);
        when(catalog.looksLikeOllama(FEDORA)).thenReturn(true);

        assertThat(serviceFor("OPENAI_COMPATIBLE", FEDORA, catalog).effectiveKind(USER))
                .isEqualTo(ProviderKind.OLLAMA);
    }

    /** Um vLLM ou um DGX de verdade continuam sendo compatíveis com OpenAI, e nada muda para eles. */
    @Test
    void mantemOTipoQuandoOEnderecoNaoEUmOllama() {
        ProviderModelCatalog catalog = mock(ProviderModelCatalog.class);
        when(catalog.looksLikeOllama("https://dgx.interno:8000")).thenReturn(false);

        assertThat(serviceFor("OPENAI_COMPATIBLE", "https://dgx.interno:8000", catalog)
                        .effectiveKind(USER))
                .isEqualTo(ProviderKind.OPENAI_COMPATIBLE);
    }

    /** Gemini e Anthropic nunca são sondados: o tipo deles não admite essa confusão. */
    @Test
    void naoSondaProvedorGerenciado() {
        ProviderModelCatalog catalog = mock(ProviderModelCatalog.class);

        assertThat(serviceFor("GEMINI", "https://generativelanguage.googleapis.com", catalog)
                        .effectiveKind(USER))
                .isEqualTo(ProviderKind.GEMINI);
        verify(catalog, never()).looksLikeOllama(org.mockito.ArgumentMatchers.anyString());
    }

    /** A sondagem não pode entrar no caminho de cada rodada: uma vez por endereço basta. */
    @Test
    void sondaUmaVezSoPorEndereco() {
        ProviderModelCatalog catalog = mock(ProviderModelCatalog.class);
        when(catalog.looksLikeOllama(FEDORA)).thenReturn(true);
        ModelProviderService service = serviceFor("OPENAI_COMPATIBLE", FEDORA, catalog);

        service.effectiveKind(USER);
        service.effectiveKind(USER);
        service.effectiveKind(USER);

        verify(catalog, times(1)).looksLikeOllama(FEDORA);
    }

    private ModelProviderService serviceFor(String kind, String baseUrl, ProviderModelCatalog catalog) {
        ProviderSettings settings = new ProviderSettings(USER);
        settings.setProviderKind(kind);
        settings.setBaseUrl(baseUrl);

        ProviderSettingsRepository repository = mock(ProviderSettingsRepository.class);
        when(repository.findById(USER)).thenReturn(Optional.of(settings));

        ModelProviderService service = new ModelProviderService(
                providerOf(mock(StringRedisTemplate.class)),
                "http://127.0.0.1:11434",
                new ObjectMapper(),
                providerOf(repository),
                providerOf(mock(SecretCipher.class)),
                providerOf(catalog),
                // Fallback desligado: estes casos testam a deteccao de tipo, nao a queda para local.
                false);
        // O construtor resolve os ObjectProvider uma vez; garantir os campos evita depender disso.
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
