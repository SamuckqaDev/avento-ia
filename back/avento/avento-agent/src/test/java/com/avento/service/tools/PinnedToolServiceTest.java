package com.avento.service.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.avento.model.PinnedTools;
import com.avento.repository.PinnedToolsRepository;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * O que está fixado entra no toolset de toda rodada, então um erro aqui aparece como "a ferramenta
 * sumiu" no meio de uma conversa — longe da causa. Estes testes prendem a serialização, que é onde o
 * dado se perde em silêncio.
 */
class PinnedToolServiceTest {

    private static final UUID USER = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private static PinnedToolService serviceWith(PinnedToolsRepository repository) {
        ObjectProvider<PinnedToolsRepository> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(repository);
        return new PinnedToolService(provider);
    }

    @Test
    void readsBackWhatItStored() {
        PinnedToolsRepository repository = Mockito.mock(PinnedToolsRepository.class);
        Mockito.when(repository.findById(USER)).thenReturn(Optional.empty());
        PinnedToolService service = serviceWith(repository);

        service.replace(USER, new LinkedHashSet<>(Set.of("read_file")));

        Mockito.verify(repository).save(Mockito.argThat(stored -> "read_file".equals(stored.getToolNames())));
    }

    // A ordem de fixar é a ordem que a tela mostra de volta.
    @Test
    void keepsTheOrderTheUserPinnedIn() {
        PinnedToolsRepository repository = Mockito.mock(PinnedToolsRepository.class);
        Mockito.when(repository.findById(USER)).thenReturn(Optional.empty());
        PinnedToolService service = serviceWith(repository);

        LinkedHashSet<String> requested = new LinkedHashSet<>();
        requested.add("terminal_run");
        requested.add("read_file");
        requested.add("write_file");

        assertThat(service.replace(USER, requested)).containsExactly("terminal_run", "read_file", "write_file");
    }

    /**
     * Vírgula é o separador do campo. Um nome que a contenha voltaria partido em dois nomes que não
     * existem, e as duas ferramentas sumiriam do toolset sem erro nenhum.
     */
    @Test
    void stripsTheSeparatorFromToolNames() {
        PinnedToolsRepository repository = Mockito.mock(PinnedToolsRepository.class);
        Mockito.when(repository.findById(USER)).thenReturn(Optional.empty());
        PinnedToolService service = serviceWith(repository);

        assertThat(service.replace(USER, new LinkedHashSet<>(Set.of("read,file"))))
                .containsExactly("readfile");
    }

    @Test
    void parsesTheStoredList() {
        PinnedTools stored = new PinnedTools(USER);
        stored.setToolNames("read_file, terminal_run ,write_file");
        PinnedToolsRepository repository = Mockito.mock(PinnedToolsRepository.class);
        Mockito.when(repository.findById(USER)).thenReturn(Optional.of(stored));

        assertThat(serviceWith(repository).pinnedFor(USER)).containsExactly("read_file", "terminal_run", "write_file");
    }

    // Sem nada fixado o comportamento tem de ser o anterior: quem decide e o catalogo.
    @Test
    void returnsEmptyWhenNothingIsPinned() {
        PinnedToolsRepository repository = Mockito.mock(PinnedToolsRepository.class);
        Mockito.when(repository.findById(USER)).thenReturn(Optional.empty());

        assertThat(serviceWith(repository).pinnedFor(USER)).isEmpty();
    }

    // Falha de banco nao pode derrubar a rodada: sem os fixados o agente ainda funciona.
    @Test
    void survivesARepositoryFailure() {
        PinnedToolsRepository repository = Mockito.mock(PinnedToolsRepository.class);
        Mockito.when(repository.findById(USER)).thenThrow(new IllegalStateException("banco fora"));

        assertThat(serviceWith(repository).pinnedFor(USER)).isEmpty();
    }

    @Test
    void returnsEmptyWithoutARepository() {
        assertThat(serviceWith(null).pinnedFor(USER)).isEmpty();
        assertThat(serviceWith(null).replace(USER, Set.of("read_file"))).isEmpty();
    }
}
