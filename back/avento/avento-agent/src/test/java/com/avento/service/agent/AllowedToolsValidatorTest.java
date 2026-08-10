package com.avento.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.dto.SelectableTool;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * O perfil de um agente só pode pedir ferramenta que existe — e a recusa tem de vir na gravação,
 * quando a pessoa ainda está olhando para a tela.
 */
class AllowedToolsValidatorTest {

    private final SelectableToolCatalog catalog = mock(SelectableToolCatalog.class);
    private final AllowedToolsValidator validator = new AllowedToolsValidator(catalog);

    private SelectableTool tool(String name, String source, boolean requiresConnection) {
        return new SelectableTool(name, source, "", "", "", "", requiresConnection);
    }

    private void catalogHas(SelectableTool... tools) {
        when(catalog.all()).thenReturn(List.of(tools));
    }

    @Test
    void acceptsToolsThatExist() {
        catalogHas(
                tool("read_file", SelectableTool.SOURCE_LOCAL, false),
                tool("write_file", SelectableTool.SOURCE_LOCAL, false));

        assertThat(validator.validateAndCanonicalise("read_file,write_file")).isEqualTo("read_file,write_file");
    }

    /**
     * Nome inexistente é erro de CONFIGURAÇÃO, e a mensagem precisa dizer qual.
     *
     * <p>Deixar passar produz um agente que nunca vai poder fazer o que promete, e a pessoa só
     * descobre no meio de uma conversa — longe da tela onde poderia consertar.
     */
    @Test
    void refusesAToolThatExistsNowhereAndNamesIt() {
        catalogHas(tool("read_file", SelectableTool.SOURCE_LOCAL, false));

        assertThatThrownBy(() -> validator.validateAndCanonicalise("read_file,ferramenta_que_nao_existe"))
                .isInstanceOf(AllowedToolsValidator.UnknownToolsException.class)
                .hasMessageContaining("ferramenta_que_nao_existe");
    }

    /**
     * <b>A distinção que decide o comportamento.</b> Ferramenta de container desligado é conhecida —
     * só não está conectada agora. Barrar aqui transformaria "meu container está parado" em "você não
     * pode salvar este agente", e o servidor sobe quando a ferramenta for usada, não quando for
     * escolhida.
     */
    @Test
    void acceptsAToolWhoseServerIsCurrentlyDown() {
        catalogHas(tool("fetch", SelectableTool.SOURCE_CONTAINER, true));

        assertThat(validator.validateAndCanonicalise("fetch")).isEqualTo("fetch");
    }

    /**
     * A ordem sai canônica.
     *
     * <p>Não é estética: a lista do perfil vira o conjunto de ferramentas da rodada, e o payload de
     * ferramentas entra no PREFIXO do prompt. Prefixo que muda entre mensagens invalida o cache do
     * llama.cpp — já custou cerca de 50 segundos por resposta.
     */
    @Test
    void producesTheSameListRegardlessOfTheOrderItWasTypedIn() {
        catalogHas(
                tool("write_file", SelectableTool.SOURCE_LOCAL, false),
                tool("read_file", SelectableTool.SOURCE_LOCAL, false),
                tool("fetch", SelectableTool.SOURCE_CONTAINER, true));

        assertThat(validator.validateAndCanonicalise("write_file,fetch,read_file"))
                .isEqualTo(validator.validateAndCanonicalise("read_file,write_file,fetch"))
                .isEqualTo("fetch,read_file,write_file");
    }

    @Test
    void dropsRepeatedNames() {
        catalogHas(tool("read_file", SelectableTool.SOURCE_LOCAL, false));

        assertThat(validator.validateAndCanonicalise("read_file,read_file")).isEqualTo("read_file");
    }

    @Test
    void toleratesSpacesAroundTheNames() {
        catalogHas(
                tool("read_file", SelectableTool.SOURCE_LOCAL, false),
                tool("write_file", SelectableTool.SOURCE_LOCAL, false));

        assertThat(validator.validateAndCanonicalise(" read_file , write_file "))
                .isEqualTo("read_file,write_file");
    }

    /** Lista vazia é "sem restrição" — a escolha do agente Generalista, não um erro. */
    @Test
    void letsAnEmptyListThroughBecauseNoRestrictionIsALegitimateChoice() {
        assertThat(validator.validateAndCanonicalise("")).isEmpty();
        assertThat(validator.validateAndCanonicalise(null)).isNull();
    }
}
