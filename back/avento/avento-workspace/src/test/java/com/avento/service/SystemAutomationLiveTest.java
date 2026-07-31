package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.avento.service.dto.SystemActionResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercita de verdade as ferramentas que agem sobre a máquina: abrir aplicativo, revelar no Finder,
 * capturar a tela.
 *
 * <p><b>Não roda por padrão.</b> Estes testes abrem janela e tiram print de quem os executa — num
 * runner de CI isso não faz sentido, e na máquina de alguém, sem aviso, é invasivo. Rode de
 * propósito:
 *
 * <pre>mvn test -Dtest=SystemAutomationLiveTest -Davento.livetools=true -Dsurefire.failIfNoSpecifiedTests=false</pre>
 *
 * <p>O que fica de fora mesmo assim: {@code run_shortcut}, porque um atalho do macOS pode fazer
 * qualquer coisa e não há atalho conhecido para usar de cobaia; e {@code close_app} contra qualquer
 * aplicativo que o teste não tenha aberto — fechar o editor de alguém perde trabalho.
 */
@EnabledOnOs(OS.MAC)
@EnabledIfSystemProperty(named = "avento.livetools", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SystemAutomationLiveTest {

    /** Cobaia deliberada: app da própria Apple, sem estado, que o teste abre e fecha. */
    private static final String GUINEA_PIG_APP = "TextEdit";

    private final SystemAutomationService automation = new SystemAutomationService();

    @TempDir
    Path scratch;

    /** Sucesso = processo terminou com 0 e nao estourou o tempo. */
    private void assertSucceeded(SystemActionResult result, String tool) {
        assertThat(result.timedOut()).as("%s estourou o tempo", tool).isFalse();
        assertThat(result.exitCode())
                .as("%s falhou (status=%s): %s", tool, result.status(), result.output())
                .isZero();
    }

    @Test
    @Order(1)
    void listsTheInstalledApplications() throws Exception {
        var apps = automation.listMacApplications();

        assertThat(apps).as("list_macos_apps nao devolveu nenhum aplicativo").isNotEmpty();
        assertThat(apps.toString()).contains("TextEdit");
        // O Finder NAO aparece aqui, e isso e esperado: ele mora em /System/Library/CoreServices,
        // que nao esta entre as raizes varridas. open_app("Finder") funciona mesmo assim, porque
        // usa `open -a`. Travado para a diferenca ficar registrada em vez de virar surpresa.
        assertThat(apps.toString()).doesNotContain("name=Finder,");
    }

    @Test
    @Order(2)
    void opensAndThenClosesTheAppItOpened() throws Exception {
        assertSucceeded(automation.openApp(GUINEA_PIG_APP), "open_app");

        Thread.sleep(1_500); // o app precisa terminar de subir antes de receber o quit

        assertSucceeded(automation.closeApp(GUINEA_PIG_APP), "close_app");
    }

    @Test
    @Order(3)
    void revealsAFileInFinder() throws Exception {
        Path file = Files.writeString(scratch.resolve("revelado.txt"), "conteudo");

        assertSucceeded(automation.revealInFinder(file), "reveal_in_finder");
    }

    @Test
    @Order(4)
    void opensAPathWithTheDefaultApplication() throws Exception {
        // Uma pasta, não um arquivo: abre no Finder em vez de disparar um editor qualquer.
        Path folder = Files.createDirectory(scratch.resolve("pasta-alvo"));

        assertSucceeded(automation.openPath(folder), "open_path");
    }

    @Test
    @Order(5)
    void capturesTheScreenToAFile() {
        Path shot = scratch.resolve("captura.png");

        SystemActionResult result = automation.captureScreen(shot);

        if (result.exitCode() != 0) {
            // Sem permissao de Gravacao de Tela o macOS recusa, e nao ha o que o teste possa fazer
            // a respeito. O que ele PODE garantir e que a mensagem diga onde conceder.
            assertThat(result.output())
                    .as("a falha por permissao tem de ser acionavel, nao o erro cru do screencapture")
                    .contains("Gravação de Tela")
                    .contains("Ajustes do Sistema");
            return;
        }
        assertThat(Files.exists(shot)).as("o arquivo de captura tem de existir").isTrue();
        // Um PNG real, não um arquivo vazio deixado por um screencapture que falhou em silêncio.
        assertThat(shot.toFile().length()).isGreaterThan(1_000L);
    }

    /**
     * A URL aponta para a porta local do próprio Avento de propósito: abre o navegador sem sair da
     * máquina nem carregar página de terceiro.
     */
    @Test
    @Order(6)
    void opensALocalUrlInTheBrowser() {
        assertSucceeded(automation.openUrl("http://127.0.0.1:5173"), "open_url");
    }
}
