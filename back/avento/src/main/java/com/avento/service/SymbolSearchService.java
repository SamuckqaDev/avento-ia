package com.avento.service;

import com.avento.service.dto.SymbolMatch;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Navegação de código por símbolo, não por texto: encontra ONDE um nome é DEFINIDO (classe,
 * interface, record, enum, função, método, const/type) no workspace, em vez de listar toda menção.
 * É o que falta para o agente entender o projeto sem reler tudo. Zero dependência externa — só
 * varre os arquivos-fonte com padrões de definição por linguagem.
 */
@Service
public class SymbolSearchService {

    private static final int MAX_RESULTS = 40;
    private static final long MAX_FILE_BYTES = 2_000_000;

    private static final Set<String> SKIP_DIRS = Set.of(
            "node_modules",
            "target",
            "build",
            "dist",
            "out",
            "bin",
            ".git",
            ".idea",
            ".gradle",
            "__pycache__",
            ".venv");

    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            ".java", ".kt", ".scala", ".groovy", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".vue", ".svelte", ".py",
            ".go", ".rb", ".rs", ".php", ".cs", ".c", ".cpp", ".cc", ".h", ".hpp", ".swift", ".dart");

    private final WorkspaceAccessService workspaceAccessService;

    public SymbolSearchService(WorkspaceAccessService workspaceAccessService) {
        this.workspaceAccessService = workspaceAccessService;
    }

    public List<SymbolMatch> find(String path, String symbol) {
        Path root = workspaceAccessService.requireAuthorized(path);
        String name = symbol == null ? "" : symbol.trim();
        if (name.isBlank()) {
            return List.of();
        }
        String quoted = Pattern.quote(name);
        // Declarações de tipo/const: `class Foo`, `interface Foo`, `type Foo`, `const foo`, etc.
        Pattern declaration = Pattern.compile(
                "\\b(class|interface|enum|record|struct|trait|type|module|namespace|const|let|var|val)\\s+" + quoted
                        + "\\b");
        // Definições de função/método: NAME( precedido por def/modificador/tipo (evita casar chamadas).
        Pattern function = Pattern.compile("(^|[\\s(])(def|function|func|fn|fun|public|private|protected|static|final|"
                + "abstract|async|override|suspend|export)[\\w\\s<>\\[\\],.*&?:]*\\b" + quoted + "\\s*\\(");

        List<SymbolMatch> matches = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName() == null
                            ? ""
                            : dir.getFileName().toString().toLowerCase(Locale.ROOT);
                    return SKIP_DIRS.contains(dirName) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= MAX_RESULTS || !isSourceFile(file) || attrs.size() > MAX_FILE_BYTES) {
                        return matches.size() >= MAX_RESULTS ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }
                    scanFile(file, declaration, function, matches);
                    return matches.size() >= MAX_RESULTS ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Varredura best-effort: devolve o que já achou.
        }
        return matches;
    }

    private void scanFile(Path file, Pattern declaration, Pattern function, List<SymbolMatch> matches) {
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (matches.size() >= MAX_RESULTS) {
                    return;
                }
                String line = lines.get(i);
                if (declaration.matcher(line).find() || function.matcher(line).find()) {
                    matches.add(new SymbolMatch(file.toString(), i + 1, line.strip()));
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Arquivo binário/ilegível: pula.
        }
    }

    private boolean isSourceFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SOURCE_EXTENSIONS.contains(name.substring(dot));
    }
}
