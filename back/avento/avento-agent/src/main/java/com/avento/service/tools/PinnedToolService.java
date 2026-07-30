package com.avento.service.tools;

import com.avento.model.PinnedTools;
import com.avento.repository.PinnedToolsRepository;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Ferramentas fixadas pelo usuário — as que entram no toolset de toda rodada.
 *
 * <p>Complementa a descoberta progressiva do {@link ToolCatalogService}, não a substitui. O catálogo
 * continua sendo o caminho normal, porque mandar todos os schemas de uma vez enche a janela de
 * contexto; o que muda é que agora existe um jeito de garantir uma ferramenta específica sem
 * depender de o modelo chamar {@code activate_tools}.
 *
 * <p>Sem repositório (testes fora do Spring), responde lista vazia em vez de quebrar: o efeito é
 * exatamente o comportamento anterior, com tudo decidido pelo catálogo.
 */
@Service
public class PinnedToolService {

    private static final Logger logger = LoggerFactory.getLogger(PinnedToolService.class);
    private static final String SEPARATOR = ",";

    private final PinnedToolsRepository repository;

    public PinnedToolService(ObjectProvider<PinnedToolsRepository> repositoryProvider) {
        this.repository = repositoryProvider == null ? null : repositoryProvider.getIfAvailable();
    }

    /** Nomes fixados por este usuário. Conjunto vazio quando não há nada fixado. */
    public Set<String> pinnedFor(UUID userId) {
        if (userId == null || repository == null) {
            return Set.of();
        }
        try {
            return repository
                    .findById(userId)
                    .map(stored -> split(stored.getToolNames()))
                    .orElseGet(Set::of);
        } catch (RuntimeException exception) {
            // Uma falha de leitura aqui nao pode derrubar a rodada: sem os fixados o agente ainda
            // funciona pelo catalogo, que e o caminho padrao.
            logger.warn(
                    "Falha ao ler as ferramentas fixadas: {}",
                    exception.getClass().getSimpleName());
            return Set.of();
        }
    }

    /** Substitui a lista inteira. A tela sempre manda o estado completo, não um delta. */
    public Set<String> replace(UUID userId, Set<String> toolNames) {
        if (userId == null || repository == null) {
            return Set.of();
        }
        Set<String> normalized = normalize(toolNames);
        try {
            PinnedTools stored = repository.findById(userId).orElseGet(() -> new PinnedTools(userId));
            stored.setToolNames(String.join(SEPARATOR, normalized));
            repository.save(stored);
        } catch (RuntimeException exception) {
            logger.warn(
                    "Falha ao gravar as ferramentas fixadas: {}",
                    exception.getClass().getSimpleName());
        }
        return normalized;
    }

    /** LinkedHashSet: a ordem em que o usuário fixou é a ordem em que ele espera reler a lista. */
    private static Set<String> normalize(Set<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return toolNames.stream()
                .filter(name -> name != null && !name.isBlank())
                // Virgula e o separador do campo; um nome com virgula quebraria a leitura de volta.
                .map(name -> name.trim().replace(SEPARATOR, ""))
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> split(String stored) {
        if (stored == null || stored.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(stored.split(SEPARATOR))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
