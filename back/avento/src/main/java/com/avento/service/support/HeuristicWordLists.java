package com.avento.service.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;

// Loads flat word/phrase lists used by heuristic message classification (casual-message
// detection, intent routing) from plain text resources instead of Java literals, so editing a
// keyword only touches a text file.
public final class HeuristicWordLists {

    private HeuristicWordLists() {}

    public static List<String> loadLines(String classpathResource) {
        List<String> words = new ArrayList<>();
        for (String rawLine : readLines(classpathResource)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            words.add(line);
        }
        return List.copyOf(words);
    }

    // Parses a file split into [SECTION] headers, each followed by one word per line.
    public static Map<String, List<String>> loadSections(String classpathResource) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        String currentSection = null;
        List<String> currentWords = null;

        for (String rawLine : readLines(classpathResource)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length() - 1).trim();
                currentWords = new ArrayList<>();
                sections.put(currentSection, currentWords);
                continue;
            }
            if (currentWords == null) {
                throw new IllegalStateException(
                        "Word found before any [SECTION] header in " + classpathResource + ": " + line);
            }
            currentWords.add(line);
        }

        sections.replaceAll((section, words) -> List.copyOf(words));
        return Map.copyOf(sections);
    }

    private static List<String> readLines(String classpathResource) {
        try (InputStream inputStream = new ClassPathResource(classpathResource).getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível carregar " + classpathResource, exception);
        }
    }
}
