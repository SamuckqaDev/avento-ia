package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.service.dto.SymbolMatch;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymbolSearchServiceTest {

    private SymbolSearchService service(Path root) {
        WorkspaceAccessService workspace = mock(WorkspaceAccessService.class);
        when(workspace.requireAuthorized(any(String.class))).thenReturn(root);
        return new SymbolSearchService(workspace);
    }

    @Test
    void findsAJavaClassAndMethodDefinitionButNotACall(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Foo.java"), "package a;\npublic class Foo {\n  public void doStuff() {}\n}\n");
        Files.writeString(
                root.resolve("Bar.java"), "package a;\nclass Bar {\n  void run() { new Foo().doStuff(); }\n}\n");

        List<SymbolMatch> foo = service(root).find(root.toString(), "Foo");
        assertThat(foo).anyMatch(m -> m.text().contains("class Foo"));
        // A chamada `new Foo()` em Bar.java NAO deve entrar como definicao.
        assertThat(foo).noneMatch(m -> m.file().endsWith("Bar.java"));

        List<SymbolMatch> method = service(root).find(root.toString(), "doStuff");
        assertThat(method).anyMatch(m -> m.text().contains("void doStuff"));
        assertThat(method).noneMatch(m -> m.text().contains("new Foo().doStuff()"));
    }

    @Test
    void findsTypeScriptDefinitionsAndSkipsHeavyDirectories(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("api.ts"), "export function loadUser() {}\nexport const API = '/x';\n");
        Path nodeModules = Files.createDirectories(root.resolve("node_modules").resolve("pkg"));
        Files.writeString(nodeModules.resolve("index.ts"), "export function loadUser() {}\n");

        List<SymbolMatch> matches = service(root).find(root.toString(), "loadUser");

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).file()).endsWith("api.ts");
    }

    @Test
    void returnsEmptyForABlankSymbol(@TempDir Path root) {
        assertThat(service(root).find(root.toString(), "  ")).isEmpty();
    }
}
