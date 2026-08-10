package com.avento.service.agent;

import com.avento.dto.SelectableTool;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Recusa um perfil que peça ferramenta que não existe — na GRAVAÇÃO, não na execução.
 *
 * <h2>A distinção que decide o comportamento</h2>
 *
 * Duas situações parecem a mesma e exigem tratamento oposto:
 *
 * <ul>
 *   <li><b>Ferramenta desconhecida do catálogo</b> — erro de configuração. Barra aqui, com o nome
 *       errado na mensagem. Deixar passar produz um agente que nunca vai poder fazer o que promete,
 *       e a pessoa só descobre no meio de uma conversa.
 *   <li><b>Ferramenta conhecida cujo servidor está fora do ar</b> — degradação, não erro. NÃO barra:
 *       o servidor sobe quando a ferramenta for usada, e um container desligado no momento da
 *       gravação não diz nada sobre o momento do uso.
 * </ul>
 *
 * <p>Confundir os dois é o que transformaria "meu container está parado agora" em "você não pode
 * salvar este agente".
 *
 * <h2>Ordem canônica</h2>
 *
 * A lista sai ordenada e sem repetição. Não é estética: a lista do perfil vira o conjunto de
 * ferramentas da rodada, e o payload de ferramentas entra no PREFIXO do prompt. Prefixo que muda
 * entre mensagens invalida o cache do llama.cpp — foi isso que já custou cerca de 50 segundos por
 * resposta. Duas gravações com as mesmas ferramentas têm de produzir exatamente a mesma lista.
 */
@Service
public class AllowedToolsValidator {

    /** Erro de configuração do perfil: nomes que não existem em catálogo nenhum. */
    public static class UnknownToolsException extends IllegalArgumentException {
        private final List<String> unknownTools;

        public UnknownToolsException(List<String> unknownTools) {
            super("Ferramentas desconhecidas: " + String.join(", ", unknownTools));
            this.unknownTools = List.copyOf(unknownTools);
        }

        public List<String> unknownTools() {
            return unknownTools;
        }
    }

    private final SelectableToolCatalog catalog;

    public AllowedToolsValidator(SelectableToolCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Valida e canonicaliza a lista CSV de ferramentas de um perfil.
     *
     * <p>Lista vazia ou nula passa direto: "sem restrição" é uma escolha legítima, e é o que o
     * agente Generalista usa.
     *
     * @return a mesma lista, sem repetição, ordenada, em CSV
     * @throws UnknownToolsException quando algum nome não existe em catálogo nenhum
     */
    public String validateAndCanonicalise(String allowedToolsCsv) {
        if (allowedToolsCsv == null || allowedToolsCsv.isBlank()) {
            return allowedToolsCsv;
        }

        List<String> requested = Arrays.stream(allowedToolsCsv.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
        if (requested.isEmpty()) {
            return allowedToolsCsv;
        }

        Set<String> known = new LinkedHashSet<>();
        catalog.all().stream().map(SelectableTool::name).forEach(known::add);

        List<String> unknown = new ArrayList<>();
        for (String name : requested) {
            if (!known.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new UnknownToolsException(unknown);
        }

        return new java.util.TreeSet<>(requested)
                .stream().reduce((a, b) -> a + "," + b).orElse("");
    }
}
