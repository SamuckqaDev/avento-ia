package com.avento.service;

import com.avento.service.dto.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class SystemAutomationService {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(12);
    private static final int MAX_LABEL_LENGTH = 120;
    private static final int MAX_OUTPUT_CHARS = 12000;
    private static final List<String> GENERIC_APP_WORDS = List.of("abre", "abrir", "abra", "open", "app", "aplicativo");

    public SystemActionResult openApp(String appName) {
        String cleanAppName = resolveMacApplicationName(cleanLabel(appName, "App name"));
        return runMacCommand(List.of("open", "-a", cleanAppName));
    }

    public SystemActionResult closeApp(String appName) {
        String cleanAppName = cleanLabel(appName, "App name");
        appleScriptString(cleanAppName);
        cleanAppName = resolveMacApplicationName(cleanAppName);
        String appleScriptAppName = appleScriptString(cleanAppName);
        return runMacCommand(List.of("osascript", "-e", "tell application \"" + appleScriptAppName + "\" to quit"));
    }

    public SystemActionResult openUrl(String url) {
        URI uri = parseUrl(url);
        return runMacCommand(List.of("open", uri.toString()));
    }

    public SystemActionResult openBrowserTab(String browserName) {
        return openBrowserTab(browserName, null);
    }

    public SystemActionResult openBrowserTab(String browserName, String url) {
        String cleanBrowserName = resolveMacApplicationName(cleanLabel(browserName, "Browser name"));
        String appleScriptBrowserName = appleScriptString(cleanBrowserName);
        String tabUrl =
                url == null || url.isBlank() ? "about:blank" : parseUrl(url).toString();
        String appleScriptUrl = appleScriptString(tabUrl);

        String script = "Safari".equals(cleanBrowserName)
                ? safariNewTabScript(appleScriptBrowserName, appleScriptUrl)
                : chromiumNewTabScript(appleScriptBrowserName, appleScriptUrl);

        return runMacCommand(List.of("osascript", "-e", script));
    }

    public SystemActionResult closeBrowserTab(String browserName) {
        String cleanBrowserName = cleanLabel(browserName, "Browser name");
        appleScriptString(cleanBrowserName);
        cleanBrowserName = resolveMacApplicationName(cleanBrowserName);
        String appleScriptBrowserName = appleScriptString(cleanBrowserName);
        String script = "Safari".equals(cleanBrowserName)
                ? safariCloseActiveTabScript(appleScriptBrowserName)
                : chromiumCloseActiveTabScript(appleScriptBrowserName);

        return runMacCommand(List.of("osascript", "-e", script));
    }

    private String chromiumNewTabScript(String browserName, String url) {
        return """
                tell application "%s"
                    activate
                    if (count of windows) = 0 then
                        make new window
                    end if
                    set newTab to make new tab at end of tabs of window 1 with properties {URL:"%s"}
                    set active tab index of window 1 to (count of tabs of window 1)
                end tell
                """.formatted(browserName, url);
    }

    private String safariNewTabScript(String browserName, String url) {
        return """
                tell application "%s"
                    activate
                    if (count of windows) = 0 then
                        make new document with properties {URL:"%s"}
                    else
                        tell window 1
                            set current tab to (make new tab with properties {URL:"%s"})
                        end tell
                    end if
                end tell
                """.formatted(browserName, url, url);
    }

    private String chromiumCloseActiveTabScript(String browserName) {
        return """
                tell application "%s"
                    if (count of windows) = 0 then return
                    tell window 1
                        if (count of tabs) > 1 then
                            delete active tab
                        else
                            set URL of active tab to "about:blank"
                        end if
                    end tell
                end tell
                """.formatted(browserName);
    }

    private String safariCloseActiveTabScript(String browserName) {
        return """
                tell application "%s"
                    if (count of windows) = 0 then return
                    tell window 1
                        if (count of tabs) > 1 then
                            close current tab
                        else
                            set URL of current tab to "about:blank"
                        end if
                    end tell
                end tell
                """.formatted(browserName);
    }

    public SystemActionResult openPath(Path path) {
        Path target = requireExistingPath(path);
        return runMacCommand(List.of("open", target.toString()));
    }

    public SystemActionResult revealInFinder(Path path) {
        Path target = requireExistingPath(path);
        return runMacCommand(List.of("open", "-R", target.toString()));
    }

    public SystemActionResult runShortcut(String shortcutName) {
        String cleanShortcutName = cleanLabel(shortcutName, "Shortcut name");
        return runMacCommand(List.of("shortcuts", "run", cleanShortcutName));
    }

    public List<MacApplication> listMacApplications() throws IOException {
        List<Path> roots = List.of(
                Path.of("/Applications"),
                Path.of(System.getProperty("user.home"), "Applications"),
                Path.of("/System/Applications"),
                Path.of("/System/Applications/Utilities"));
        List<MacApplication> applications = new ArrayList<>();

        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var stream = Files.walk(root, 2)) {
                stream.filter(path -> path.getFileName() != null)
                        .filter(path -> path.getFileName().toString().endsWith(".app"))
                        .map(path -> new MacApplication(appDisplayName(path), path.toString()))
                        .forEach(applications::add);
            }
        }

        return applications.stream()
                .distinct()
                .sorted(Comparator.comparing(MacApplication::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public String resolveMacApplicationName(String appNameOrUserText) {
        String cleaned = cleanLabel(appNameOrUserText, "App name");
        String normalizedInput = normalizeAppSearch(cleaned);
        try {
            MacApplication bestMatch = null;
            int bestScore = 0;
            for (MacApplication application : listMacApplications()) {
                int score = appMatchScore(normalizedInput, normalizeAppSearch(application.name()));
                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = application;
                }
            }
            if (bestMatch != null && bestScore >= 500) {
                return bestMatch.name();
            }
        } catch (IOException ignored) {
            // Falling back to aliases keeps app opening usable if filesystem listing fails.
        }
        return normalizeKnownAppAlias(cleaned);
    }

    // Used for proactive alerts (e.g. a long-running terminal_start process crashing on its own)
    // that need to reach the user even when the Avento tab isn't open — there's no persistent
    // push channel to the frontend today, so a native notification is the most direct option.
    public SystemActionResult displayNotification(String title, String message) {
        String cleanTitle = appleScriptString(cleanLabel(title, "Notification title"));
        String cleanMessage = appleScriptString(cleanLabel(message, "Notification message"));
        String script = "display notification \"" + cleanMessage + "\" with title \"" + cleanTitle + "\"";
        return runMacCommand(List.of("osascript", "-e", script));
    }

    public SystemActionResult captureScreen(Path outputPath) {
        Path target = outputPath == null ? null : outputPath.toAbsolutePath().normalize();
        List<String> command =
                target == null ? List.of("screencapture", "-x") : List.of("screencapture", "-x", target.toString());
        if (target == null || target.getParent() == null) {
            return new SystemActionResult("failed", command, -1, false, 0.0, "Screenshot output path is required.");
        }

        try {
            Files.createDirectories(target.getParent());
        } catch (IOException exception) {
            return new SystemActionResult("failed", command, -1, false, 0.0, exception.getMessage());
        }

        return runMacCommand(command);
    }

    private SystemActionResult runMacCommand(List<String> command) {
        if (!isMacOs()) {
            return new SystemActionResult(
                    "unsupported", command, -1, false, 0.0, "System automation currently supports macOS only.");
        }
        return runCommand(command, DEFAULT_TIMEOUT);
    }

    private SystemActionResult runCommand(List<String> command, Duration timeout) {
        long startedAt = System.currentTimeMillis();
        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            process = processBuilder.start();
            Process runningProcess = process;

            ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
            Thread outputReader = new Thread(() -> {
                try {
                    runningProcess.getInputStream().transferTo(outputBuffer);
                } catch (IOException ignored) {
                    // Output is best-effort; exit code still carries command status.
                }
            });
            outputReader.start();

            boolean completed = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                outputReader.join(1000);
                return new SystemActionResult(
                        "timeout",
                        command,
                        -1,
                        true,
                        elapsedSeconds(startedAt),
                        truncateOutput(outputBuffer.toString(StandardCharsets.UTF_8)));
            }

            outputReader.join(1000);
            int exitCode = process.exitValue();
            return new SystemActionResult(
                    exitCode == 0 ? "success" : "failed",
                    command,
                    exitCode,
                    false,
                    elapsedSeconds(startedAt),
                    truncateOutput(outputBuffer.toString(StandardCharsets.UTF_8)));
        } catch (IOException exception) {
            return new SystemActionResult(
                    "failed", command, -1, false, elapsedSeconds(startedAt), exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new SystemActionResult(
                    "interrupted",
                    command,
                    -1,
                    true,
                    elapsedSeconds(startedAt),
                    "System automation command was interrupted.");
        }
    }

    private boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private String cleanLabel(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }

        String cleaned = value.trim();
        if (cleaned.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException(label + " is too long.");
        }
        if (cleaned.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " contains control characters.");
        }
        return cleaned;
    }

    private String normalizeKnownAppAlias(String appName) {
        String normalized = appName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();

        return switch (normalized) {
            case "vscode",
                    "vs code",
                    "visual studio code",
                    "code",
                    "vaskode",
                    "vascode",
                    "vescode",
                    "vescunti",
                    "vesconti" -> "Visual Studio Code";
            case "chrome", "google chrome" -> "Google Chrome";
            case "brave", "brave browser" -> "Brave Browser";
            case "terminal", "terminal app" -> "Terminal";
            case "finder" -> "Finder";
            case "safari" -> "Safari";
            case "figma" -> "Figma";
            case "cursor" -> "Cursor";
            default -> appName;
        };
    }

    private int appMatchScore(String normalizedInput, String normalizedAppName) {
        if (normalizedInput.equals(normalizedAppName)) {
            return 1000 + normalizedAppName.length();
        }
        if (normalizedInput.contains(normalizedAppName)) {
            return 900 + normalizedAppName.length();
        }
        List<String> appTokens = usefulAppTokens(normalizedAppName);
        if (!appTokens.isEmpty() && appTokens.stream().allMatch(token -> containsToken(normalizedInput, token))) {
            return 700 + appTokens.size() * 30 + normalizedAppName.length();
        }
        if (normalizedAppName.contains(normalizedInput) && normalizedInput.length() >= 3) {
            return 500 + normalizedInput.length();
        }
        return 0;
    }

    private boolean containsToken(String normalizedInput, String token) {
        return (" " + normalizedInput + " ").contains(" " + token + " ");
    }

    private List<String> usefulAppTokens(String normalizedAppName) {
        return List.of(normalizedAppName.split(" ")).stream()
                .filter(token -> token.length() > 1)
                .filter(token -> !GENERIC_APP_WORDS.contains(token))
                .toList();
    }

    private String normalizeAppSearch(String value) {
        String withoutAccents = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return withoutAccents
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String appleScriptString(String value) {
        if (value.indexOf('"') >= 0 || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("App name contains unsupported AppleScript characters.");
        }
        return value;
    }

    private URI parseUrl(String url) {
        String cleaned = cleanLabel(url, "URL");
        try {
            URI uri = new URI(cleaned);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("Only http and https URLs are allowed.");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("URL host is required.");
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid URL: " + cleaned, exception);
        }
    }

    private Path requireExistingPath(Path path) {
        if (path == null || !Files.exists(path)) {
            throw new IllegalArgumentException("Path must exist.");
        }
        return path.toAbsolutePath().normalize();
    }

    private double elapsedSeconds(long startedAt) {
        return Math.round(((System.currentTimeMillis() - startedAt) / 1000.0) * 10.0) / 10.0;
    }

    private String truncateOutput(String output) {
        if (output == null || output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(output.length() - MAX_OUTPUT_CHARS);
    }

    private String appDisplayName(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".app") ? fileName.substring(0, fileName.length() - 4) : fileName;
    }
}
