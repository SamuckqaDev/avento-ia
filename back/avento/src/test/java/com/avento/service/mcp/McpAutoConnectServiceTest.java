package com.avento.service.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.service.dto.ConnectionResult;
import com.avento.service.dto.ServerDescriptor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpAutoConnectServiceTest {

    private final McpServerCatalogService catalogService = mock(McpServerCatalogService.class);
    private final McpAutoConnectService service =
            new McpAutoConnectService(catalogService, Map.of("git", List.of("commit", "branch", "git")));
    private final List<String> roots = List.of("/tmp/project");

    private ServerDescriptor descriptor(String id, boolean available, boolean connected) {
        return new ServerDescriptor(id, id, id + " server", "local", true, false, false, available, connected, "");
    }

    @Test
    void connectsAnAvailableDisconnectedServerWhenTheRequestMatches() {
        when(catalogService.catalog(roots)).thenReturn(List.of(descriptor("git", true, false)));
        when(catalogService.connect(List.of("git"), roots))
                .thenReturn(List.of(new ConnectionResult(true, "git", List.of(), "")));

        List<String> connected = service.connectRelevant("faça um commit e crie uma branch", roots);

        assertThat(connected).containsExactly("git");
    }

    @Test
    void doesNotConnectServersThatAreUnavailable() {
        // git aparece no catálogo mas NÃO está disponível (binário ausente) — não deve tentar.
        when(catalogService.catalog(roots)).thenReturn(List.of(descriptor("git", false, false)));

        List<String> connected = service.connectRelevant("faça um commit no git", roots);

        assertThat(connected).isEmpty();
        verify(catalogService, never()).connect(any(), any());
    }

    @Test
    void doesNotReconnectAlreadyConnectedServers() {
        when(catalogService.catalog(roots)).thenReturn(List.of(descriptor("git", true, true)));

        service.connectRelevant("git commit", roots);

        verify(catalogService, never()).connect(any(), any());
    }

    @Test
    void connectsNothingWhenTheRequestDoesNotMatchAnyServer() {
        when(catalogService.catalog(roots)).thenReturn(List.of(descriptor("git", true, false)));

        List<String> connected = service.connectRelevant("escreva um poema sobre o mar", roots);

        assertThat(connected).isEmpty();
        verify(catalogService, never()).connect(eq(List.of("git")), any());
    }

    @Test
    void matchesWholeWordsOnly() {
        // "git" não pode casar dentro de "digital".
        when(catalogService.catalog(roots)).thenReturn(List.of(descriptor("git", true, false)));

        assertThat(service.connectRelevant("preciso de transformacao digital", roots))
                .isEmpty();
        verify(catalogService, never()).connect(any(), any());
    }

    @Test
    void parsesKeywordsFromMarkdownGroupedByServer() {
        String markdown = "## Developer\n### git\nGatilhos: commit, branch, pull request\n\n"
                + "## Data\n### dbhub\nGatilhos: sql, postgres\n";

        Map<String, List<String>> parsed = McpAutoConnectService.parseKeywords(markdown);

        assertThat(parsed.get("git")).containsExactly("commit", "branch", "pull request");
        assertThat(parsed.get("dbhub")).containsExactly("sql", "postgres");
    }
}
