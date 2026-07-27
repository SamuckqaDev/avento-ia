package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.avento.service.tools.ToolExecutionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O sandbox chegou a ser desarmado: {@code requireAuthorized} devolvia a raiz do workspace em vez de
 * lançar quando o caminho estava fora dela, e autorizava também os diretórios PAI da raiz. Na
 * prática, {@code delete_directory("/qualquer/coisa")} recebia de volta a raiz e apagava o projeto.
 * Estes testes prendem o contrato: só descendentes de uma raiz registrada passam.
 */
class WorkspaceSandboxTest {

    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path projectRoot;

    private WorkspaceAccessService serviceWithRegisteredRoot() {
        WorkspaceAccessService service = new WorkspaceAccessService(new ToolExecutionContext());
        service.registerWorkspaceRoot(USER_ID, projectRoot.toString());
        return service;
    }

    @Test
    void authorizesFilesInsideTheRegisteredRoot() throws Exception {
        Path file = Files.writeString(projectRoot.resolve("App.java"), "código");

        assertThat(serviceWithRegisteredRoot().requireAuthorized(USER_ID, file.toString()))
                .isEqualTo(file.toRealPath());
    }

    @Test
    void rejectsPathsOutsideTheRegisteredRoot() {
        assertThatThrownBy(() -> serviceWithRegisteredRoot().requireAuthorized(USER_ID, "/etc/passwd"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsParentDirectoriesOfTheRegisteredRoot() {
        WorkspaceAccessService service = serviceWithRegisteredRoot();
        Path parent = projectRoot.getParent();

        assertThatThrownBy(() -> service.requireAuthorized(USER_ID, parent.toString()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.requireAuthorized(USER_ID, "/")).isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsTraversalOutOfTheRoot() {
        assertThatThrownBy(() -> serviceWithRegisteredRoot()
                        .requireAuthorized(
                                USER_ID, projectRoot.resolve("../fora").toString()))
                .isInstanceOf(SecurityException.class);
    }

    // registerWorkspaceRoot resolve symlinks (no macOS /var é link para /private/var), então a
    // comparação usa o caminho real — não o que o @TempDir devolve.
    private Path realRoot() throws Exception {
        return projectRoot.toRealPath();
    }

    @Test
    void resolvesPlaceholderPathsToTheRoot() throws Exception {
        WorkspaceAccessService service = serviceWithRegisteredRoot();

        assertThat(service.requireAuthorized(USER_ID, ".")).isEqualTo(realRoot());
        assertThat(service.requireAuthorized(USER_ID, "/workspace")).isEqualTo(realRoot());
    }

    // O prefixo inteiro virava a raiz, então escrever em /workspace/src/App.tsx caía na própria
    // pasta raiz em vez do arquivo pedido.
    @Test
    void resolvesPlaceholderSubpathsRelativeToTheRoot() throws Exception {
        assertThat(serviceWithRegisteredRoot().requireAuthorized(USER_ID, "/workspace/src/App.tsx"))
                .isEqualTo(realRoot().resolve("src/App.tsx"));
    }

    @Test
    void rejectsPlaceholderSubpathThatEscapesTheRoot() {
        assertThatThrownBy(() -> serviceWithRegisteredRoot().requireAuthorized(USER_ID, "/workspace/../../etc/passwd"))
                .isInstanceOf(SecurityException.class);
    }

    // Sem raiz registrada nada é autorizado: o fallback para o diretório do processo (o próprio
    // código do Avento) fica atrás de avento.workspace.allow-working-directory-fallback.
    @Test
    void rejectsEverythingWhenNoRootIsRegistered() {
        WorkspaceAccessService service = new WorkspaceAccessService(new ToolExecutionContext());

        assertThatThrownBy(() -> service.requireAuthorized(USER_ID, projectRoot.toString()))
                .isInstanceOf(SecurityException.class);
    }
}
