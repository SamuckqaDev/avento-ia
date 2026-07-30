package com.avento.service.tools;

import com.avento.service.support.CommandAllowlists;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O que o agente pode rodar no terminal, e por quanto tempo.
 *
 * <p>Esta é a fronteira de segurança mais exposta do Avento: do outro lado dela há um processo de
 * verdade na máquina do usuário. Cada regra aqui existe por causa de uma forma concreta de escapar,
 * e os comentários dizem qual — apagar um deles apaga o motivo de a regra existir.
 *
 * <p>Duas garantias sustentam o resto e não podem ser afrouxadas sem rever tudo: o comando é
 * executado por {@code ProcessBuilder} recebendo {@code List<String>}, SEM shell, então não há
 * encadeamento por {@code ;} {@code &&} {@code |}; e a ferramenta continua exigindo aprovação
 * visual antes de rodar.
 *
 * <p>Uma lista vazia significa "não permitido" — nunca é o comando vazio.
 */
public final class TerminalCommandPolicy {

    private TerminalCommandPolicy() {}

    // npm/npx sao liberados por comando inteiro, nao mais por subcomando
    // especifico — o usuario pediu explicitamente para nao precisar manter
    // uma allowlist de cada coisa que o npm sabe fazer (ex.: nest, create-*,
    // scripts customizados de package.json). O ProcessBuilder abaixo recebe
    // List<String> direto (sem invocar shell), entao nao ha risco de
    // injecao por ; && | etc. — split de espaco e seguro aqui. A ferramenta
    // continua exigindo aprovacao visual antes de rodar (ToolCapabilityRegistry).
    private static final Pattern NPM_OR_NPX_COMMAND = Pattern.compile("^(npm|npx)\\s+\\S.*$");

    // Subcomandos de leitura do git. Casamento por TOKEN EXATO, nunca por prefixo: startsWith("diff")
    // tambem casa "difftool", que com --extcmd executa um comando arbitrario.
    private static final Set<String> GIT_READ_SUBCOMMANDS =
            Set.of("status", "diff", "log", "branch", "show", "rev-parse", "remote", "tag", "describe");

    // Flags que transformam um comando de leitura em execucao arbitraria de codigo. Valem para
    // qualquer comando: git as usa em --upload-pack/--exec, mvn no plugin exec.
    private static final Set<String> CODE_EXECUTION_FLAGS = Set.of(
            "-c", "--exec", "--upload-pack", "--receive-pack", "--ext-diff", "--extcmd", "--config", "--output-file");

    // Flags de curl que gravam arquivo no disco ou leem arquivo local (exfiltracao via -d @arquivo).
    private static final Set<String> CURL_FILE_FLAGS =
            Set.of("-o", "-O", "--output", "--upload-file", "-T", "--config", "-K", "--dump-header", "-D", "--trace");

    private static final Set<String> DOCKER_READ_SUBCOMMANDS = Set.of("ps", "images", "logs", "version", "stats");

    private static final Set<String> SHELL_METACHARACTERS = Set.of("|", "||", "&", "&&", ";", ">", ">>", "<", "`");

    public static List<String> allowedTerminalCommand(String commandText) {
        String command = commandText.trim().replaceAll("\\s+", " ");
        if (command.isBlank()) {
            return List.of();
        }
        List<String> tokens = List.of(command.split(" "));
        // O comando roda via ProcessBuilder sem shell, entao "curl url | sh" nao encadeia nada — o
        // "|" e o "sh" viram argumentos do curl. Mesmo assim se rejeita: o metacaractere revela que
        // o modelo acha que esta num shell, e o pedido nao e o que ele pensa que e.
        if (tokens.stream().anyMatch(SHELL_METACHARACTERS::contains)) {
            return List.of();
        }
        if (containsAnyFlag(tokens, CODE_EXECUTION_FLAGS)) {
            return List.of();
        }

        if ("git".equals(tokens.get(0))) {
            return tokens.size() > 1 && GIT_READ_SUBCOMMANDS.contains(tokens.get(1)) ? tokens : List.of();
        }

        if ("curl".equals(tokens.get(0))) {
            return allowedCurlCommand(tokens);
        }

        if (NPM_OR_NPX_COMMAND.matcher(command).matches()) {
            return tokens;
        }

        // Goals de leitura/validacao apenas. `mvn` livre permite `mvn exec:exec
        // -Dexec.executable=/bin/sh`, que e execucao arbitraria com outro nome.
        if ("mvn".equals(tokens.get(0))) {
            boolean goalsAllowed = tokens.stream()
                    .skip(1)
                    .filter(token -> !token.startsWith("-"))
                    .allMatch(CommandAllowlists.MAVEN_GOALS::contains);
            boolean hasGoal = tokens.stream().skip(1).anyMatch(token -> !token.startsWith("-"));
            return hasGoal && goalsAllowed ? tokens : List.of();
        }

        Matcher rmMatcher = Pattern.compile("^rm -rf (\\S+)$").matcher(command);
        if (rmMatcher.matches() && isSafeRelativePathTarget(rmMatcher.group(1))) {
            return List.of("rm", "-rf", rmMatcher.group(1));
        }

        Matcher mkdirMatcher = Pattern.compile("^mkdir -p (\\S+)$").matcher(command);
        if (mkdirMatcher.matches() && isSafeRelativePathTarget(mkdirMatcher.group(1))) {
            return List.of("mkdir", "-p", mkdirMatcher.group(1));
        }

        // `docker run -v /:/mnt` monta o disco inteiro dentro do container com privilegio de root,
        // entao so subcomandos de inspecao passam.
        if ("docker".equals(tokens.get(0))) {
            if (tokens.size() > 2 && "compose".equals(tokens.get(1))) {
                return DOCKER_READ_SUBCOMMANDS.contains(tokens.get(2)) ? tokens : List.of();
            }
            return tokens.size() > 1 && DOCKER_READ_SUBCOMMANDS.contains(tokens.get(1)) ? tokens : List.of();
        }

        // Leitura local: o processo roda com o workspace como diretorio de trabalho, entao um
        // caminho relativo fica contido nele. Caminho absoluto ou ".." escapa (`cat ~/.ssh/id_rsa`).
        if ("ls".equals(tokens.get(0)) || "cat".equals(tokens.get(0)) || "head".equals(tokens.get(0))) {
            boolean targetsInsideWorkspace = tokens.stream()
                    .skip(1)
                    .filter(token -> !token.startsWith("-"))
                    .allMatch(TerminalCommandPolicy::isSafeRelativePathTarget);
            return targetsInsideWorkspace ? tokens : List.of();
        }
        if ("pwd".equals(tokens.get(0)) || "echo".equals(tokens.get(0))) {
            return tokens;
        }

        return List.of();
    }

    private static List<String> allowedCurlCommand(List<String> tokens) {
        if (containsAnyFlag(tokens, CURL_FILE_FLAGS)) {
            return List.of();
        }
        // Exatamente uma URL http(s) e nenhum argumento lendo arquivo local (-d @segredo).
        List<String> urls = tokens.stream()
                .skip(1)
                .filter(token -> token.startsWith("http://") || token.startsWith("https://"))
                .toList();
        boolean readsLocalFile = tokens.stream().anyMatch(token -> token.startsWith("@") || token.contains("=@"));
        return urls.size() == 1 && !readsLocalFile ? tokens : List.of();
    }

    private static boolean containsAnyFlag(List<String> tokens, Set<String> flags) {
        return tokens.stream().anyMatch(token -> {
            String flag = token.contains("=") ? token.substring(0, token.indexOf('=')) : token;
            return flags.contains(flag);
        });
    }

    // npm/npx installs (create-*, @scope/cli new, install) routinely take longer than 120s on a
    // cold npx cache or slow registry — 120s was timing out real scaffolds like
    // `npx @nestjs/cli new .`. Other commands here (git/mvn/docker) are fast, so they keep the
    // short default.
    public static int defaultTerminalTimeoutSeconds(String commandText) {
        return NPM_OR_NPX_COMMAND.matcher(commandText.trim()).matches() ? 240 : 120;
    }

    // rm -rf and mkdir -p both run inside the workspace directory ProcessBuilder was given (see
    // executeTerminalRun), so the target must resolve relative to it — an absolute path or a ".."
    // segment could escape the authorized workspace root entirely.
    private static boolean isSafeRelativePathTarget(String target) {
        if (target.startsWith("/") || target.startsWith("~")) {
            return false;
        }
        for (String segment : target.split("/")) {
            if (segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    public static List<String> allowedLongRunningCommand(String commandText) {
        String command = commandText.trim().replaceAll("\\s+", " ");
        // Mesma regra ampla do terminal_run — cobre scripts de dev/watch que
        // variam de nome por projeto (start:dev, dev, watch...), nao só "dev".
        if (NPM_OR_NPX_COMMAND.matcher(command).matches()) {
            return List.of(command.split(" "));
        }
        if (command.equals("mvn spring-boot:run")) {
            return List.of("mvn", "spring-boot:run");
        }
        return List.of();
    }
}
