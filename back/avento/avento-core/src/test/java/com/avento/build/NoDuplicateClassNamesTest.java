package com.avento.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Uma classe duplicada entre módulos só é tolerada enquanto as cópias forem idênticas.
 *
 * <p>Isso compila sem um aviso sequer, e quem vence é a ordem do classpath. Foi assim que
 * {@code schedule_task} ficou inalcançável: {@code com.avento.service.tools.LocalToolNames} existia
 * em avento-agent e em avento-mcp, diferindo por essa única entrada, e a cópia sem ela venceu na
 * aplicação montada — o despachante respondia "Tool not found" para uma ferramenta registrada,
 * documentada e exposta ao modelo.
 *
 * <p>Existem hoje 22 nomes duplicados no projeto, todos com cópias iguais — DTOs repetidos entre
 * avento-media e avento-workspace, na maioria. Enquanto forem byte a byte iguais, qual delas o
 * classpath escolhe é indiferente. O perigo é a divergência, e é ela que este teste proíbe: no
 * instante em que alguém edita uma cópia e esquece a outra, o build quebra em vez de produzir um
 * defeito silencioso em tempo de execução.
 *
 * <p>Unificar as 22 é dívida legítima e continua valendo — este teste segura a ponte até lá.
 *
 * <p>A varredura é sobre os fontes, não sobre os jars, de propósito: o {@code banDuplicateClasses}
 * do enforcer também pega isto, mas junto de colisões de terceiros (commons-logging contra
 * spring-jcl, entre outras) que exigiriam uma lista de exceções para manter viva. Aqui só o código
 * do projeto entra, então não há ruído nem manutenção.
 */
class NoDuplicateClassNamesTest {

    @Test
    void everyDuplicatedClassHasIdenticalCopies() throws IOException {
        Path backend = Path.of("..").toAbsolutePath().normalize();
        Map<String, List<Path>> filesByClassName = new LinkedHashMap<>();

        try (Stream<Path> modules = Files.list(backend)) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                collectInto(filesByClassName, module);
            }
        }

        List<String> diverged = new ArrayList<>();
        for (Map.Entry<String, List<Path>> entry : filesByClassName.entrySet()) {
            List<Path> copies = entry.getValue();
            if (copies.size() < 2) {
                continue;
            }
            String first = Files.readString(copies.get(0));
            for (Path other : copies.subList(1, copies.size())) {
                if (!first.equals(Files.readString(other))) {
                    diverged.add(entry.getKey() + " diverge entre " + copies);
                    break;
                }
            }
        }

        assertThat(diverged)
                .as("copias divergentes da mesma classe: qual vence depende da ordem do classpath,"
                        + " entao a diferenca vira defeito silencioso em execucao")
                .isEmpty();
    }

    private void collectInto(Map<String, List<Path>> filesByClassName, Path module) throws IOException {
        Path sources = module.resolve("src/main/java");
        if (!Files.isDirectory(sources)) {
            return;
        }
        try (Stream<Path> files = Files.walk(sources)) {
            files.filter(file -> file.toString().endsWith(".java")).forEach(file -> {
                String className = sources.relativize(file)
                        .toString()
                        .replace(".java", "")
                        .replace(java.io.File.separatorChar, '.');
                filesByClassName
                        .computeIfAbsent(className, key -> new ArrayList<>())
                        .add(file);
            });
        }
    }
}
