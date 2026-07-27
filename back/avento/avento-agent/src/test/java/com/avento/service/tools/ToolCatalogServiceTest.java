package com.avento.service.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.service.tools.ToolCatalogService.CapabilitySummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Descoberta progressiva de ferramentas: com o orçamento de contexto de uma máquina de 16GB não dá
 * para enviar todos os schemas por rodada, então o modelo procura a capacidade e ativa só o que
 * precisa. Sem Redis o serviço degrada para o par de descoberta em memória.
 */
class ToolCatalogServiceTest {

    @SuppressWarnings("unchecked")
    private ToolCatalogService newService() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new ToolCatalogService(provider, new ToolCapabilityRegistry(), new ObjectMapper());
    }

    // Uma consulta natural ("gerar pdf relatorio") não pode exigir a frase inteira na descrição.
    @Test
    void searchMatchesByIndividualTokensNotWholePhrase() {
        List<CapabilitySummary> results = newService().searchCapabilities("gerar pdf relatorio");

        assertThat(results).anyMatch(summary -> summary.toolId().equals("generate_pdf"));
    }

    @Test
    void searchIncludesExternalExtrasWithoutDuplicatingRegistryEntries() {
        CapabilitySummary external =
                new CapabilitySummary("fetch", "fetch", "MCP_EXTERNAL:fetch", "Le uma pagina web ou API.");
        CapabilitySummary duplicate =
                new CapabilitySummary("generate_pdf", "generate_pdf", "X", "duplicata que nao deve entrar");

        List<CapabilitySummary> results = newService().searchCapabilities("web pagina", List.of(external, duplicate));

        assertThat(results).anyMatch(summary -> summary.toolId().equals("fetch"));
        assertThat(results.stream()
                        .filter(summary -> summary.toolId().equals("generate_pdf"))
                        .count())
                .isLessThanOrEqualTo(1);
    }

    // A validação contra o registro local rejeitava toda ferramenta externa — activate_tools não
    // conseguia ativar exatamente as ferramentas que mais dependem dela.
    @Test
    void activatesExternalToolsPresentInTheRoundCatalog() {
        Set<String> active = newService().activateTools("run_test", List.of("fetch", "inventada"), Set.of("fetch"));

        assertThat(active).contains("fetch");
        assertThat(active).doesNotContain("inventada");
    }

    @Test
    void activatesLocalRegistryToolsWithoutExtraValidNames() {
        assertThat(newService().activateTools("run_test", List.of("generate_pdf")))
                .contains("generate_pdf");
    }

    @Test
    void activeSetAlwaysKeepsTheDiscoveryPair() {
        assertThat(newService().getActiveTools("run_x")).contains("search_capabilities", "activate_tools");
    }
}
