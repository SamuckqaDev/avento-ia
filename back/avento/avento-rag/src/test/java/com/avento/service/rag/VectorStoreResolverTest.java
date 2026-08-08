package com.avento.service.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Escolher outro modelo de embedding gravava uma linha no banco e não mudava mais nada: o store vinha
 * da autoconfiguração, fixado no boot ao modelo do YAML. Pior que não fazer nada, porque a tela
 * mostrava a escolha como se valesse.
 */
class VectorStoreResolverTest {

    @Test
    void cadaModeloGanhaSeuProprioIndice() {
        VectorStoreResolver resolver = resolverFor(new EmbeddingProfile("bge-m3:latest", mock(EmbeddingModel.class)));

        // Vetor de modelos diferentes não é comparável e nem tem a mesma largura — nomic devolve 768
        // números e bge-m3 devolve 1024. Um índice por modelo é o que impede a mistura.
        assertThat(resolver.activeIndexName()).isEqualTo("avento_index_bge_m3_latest");
    }

    @Test
    void semEscolhaGravadaFicaNoIndiceDaAutoconfiguracao() {
        VectorStoreResolver resolver = resolverFor(null);

        assertThat(resolver.activeIndexName()).isEqualTo("avento_index");
    }

    @Test
    void umaLeituraQueFalhaNaoDerrubaABusca() {
        EmbeddingProfileSource source = mock(EmbeddingProfileSource.class);
        when(source.active()).thenThrow(new IllegalStateException("banco fora do ar"));
        VectorStore autoConfigured = mock(VectorStore.class);

        VectorStoreResolver resolver = new VectorStoreResolver(
                autoConfigured, providerOf(null), providerOf(source), "avento_index", "avento:");

        assertThat(resolver.activeIndexName()).isEqualTo("avento_index");
        assertThat(resolver.active()).isSameAs(autoConfigured);
    }

    @Test
    void perfilSemModeloNaoConta() {
        VectorStoreResolver resolver = resolverFor(new EmbeddingProfile("  ", mock(EmbeddingModel.class)));

        assertThat(resolver.activeIndexName()).isEqualTo("avento_index");
    }

    private VectorStoreResolver resolverFor(EmbeddingProfile profile) {
        EmbeddingProfileSource source = mock(EmbeddingProfileSource.class);
        when(source.active()).thenReturn(Optional.ofNullable(profile));
        return new VectorStoreResolver(
                mock(VectorStore.class), providerOf(null), providerOf(source), "avento_index", "avento:");
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
