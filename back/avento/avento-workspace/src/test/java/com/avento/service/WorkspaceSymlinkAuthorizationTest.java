package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.avento.service.tools.ToolExecutionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A autorização de workspace tem de julgar o caminho por onde ele REALMENTE cai, não pelo texto.
 *
 * <p>A raiz é registrada com {@code toRealPath()}, mas um arquivo que ainda não existe não tinha
 * como ser resolvido — ficava só em {@code toAbsolutePath().normalize()}. Isso quebrava nos dois
 * sentidos, e os dois estão travados aqui.
 */
class WorkspaceSymlinkAuthorizationTest {

    @TempDir
    Path tempDir;

    /**
     * O proprio @TempDir vive sob /var, que no macOS ja e um link para /private/var. Sem resolver
     * isso primeiro, o teste de escape passaria pelo motivo errado — recusado por causa do /var, nao
     * por causa do link que ele quer exercitar.
     */
    private Path scratch;

    private WorkspaceAccessService workspaceAccess;

    @BeforeEach
    void setUp() throws Exception {
        scratch = tempDir.toRealPath();
        workspaceAccess = new WorkspaceAccessService(new ToolExecutionContext());
    }

    /**
     * No macOS {@code /tmp} e {@code /var} são links para {@code /private/...}. Um projeto ali podia
     * ser lido mas não escrito: criar arquivo era recusado como "fora do workspace" — sendo que
     * estava dentro.
     */
    @Test
    void allowsCreatingAFileInsideAWorkspaceReachedThroughASymlink() throws Exception {
        Path realWorkspace = Files.createDirectory(scratch.resolve("projeto-real"));
        Path linkToWorkspace = Files.createSymbolicLink(scratch.resolve("atalho-projeto"), realWorkspace);
        workspaceAccess.registerWorkspaceRoot(linkToWorkspace.toString());

        Path newFile = linkToWorkspace.resolve("ainda-nao-existe.txt");
        Path authorized = workspaceAccess.requireAuthorized(newFile.toString());

        assertThat(authorized).isEqualTo(realWorkspace.toRealPath().resolve("ainda-nao-existe.txt"));
    }

    /** Ler um arquivo que já existe sempre funcionou; fica travado para não regredir. */
    @Test
    void keepsAllowingAnExistingFileInsideTheWorkspace() throws Exception {
        Path realWorkspace = Files.createDirectory(scratch.resolve("projeto"));
        workspaceAccess.registerWorkspaceRoot(realWorkspace.toString());
        Path existing = Files.writeString(realWorkspace.resolve("ja-existe.txt"), "conteudo");

        assertThat(workspaceAccess.requireAuthorized(existing.toString())).isEqualTo(existing.toRealPath());
    }

    /**
     * O lado grave: um link DENTRO do workspace apontando para fora. O caminho textual começa com a
     * raiz, então a verificação antiga aprovava — e a escrita ia parar fora do workspace.
     */
    @Test
    void refusesANewFileThatWouldLandOutsideThroughASymlink() throws Exception {
        Path workspace = Files.createDirectory(scratch.resolve("workspace"));
        Path outside = Files.createDirectory(scratch.resolve("fora"));
        workspaceAccess.registerWorkspaceRoot(workspace.toString());
        Files.createSymbolicLink(workspace.resolve("atalho"), outside);

        Path escaping = workspace.resolve("atalho").resolve("novo.txt");

        assertThatThrownBy(() -> workspaceAccess.requireAuthorized(escaping.toString()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("outside the authorized workspace");
    }

    /** Um caminho fora da raiz, sem link nenhum, continua recusado. */
    @Test
    void keepsRefusingAPlainPathOutsideTheWorkspace() throws Exception {
        Path workspace = Files.createDirectory(scratch.resolve("workspace"));
        workspaceAccess.registerWorkspaceRoot(workspace.toString());

        assertThatThrownBy(() -> workspaceAccess.requireAuthorized(
                        scratch.resolve("outro").resolve("arquivo.txt").toString()))
                .isInstanceOf(SecurityException.class);
    }

    /** Subpasta nova dentro da raiz — o caso comum de create_directory — continua permitida. */
    @Test
    void allowsANewNestedDirectoryInsideTheWorkspace() throws Exception {
        Path workspace = Files.createDirectory(scratch.resolve("workspace"));
        workspaceAccess.registerWorkspaceRoot(workspace.toString());

        Path nested = workspace.resolve("src").resolve("main").resolve("Novo.java");

        assertThat(workspaceAccess.requireAuthorized(nested.toString()))
                .isEqualTo(workspace.toRealPath().resolve("src/main/Novo.java"));
    }
}
