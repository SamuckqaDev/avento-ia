package com.avento.service.support;

import com.avento.service.dto.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Loads named, reusable procedures ("skills") from two places: built-in ones shipped in the jar
 * (`classpath:agent/skills/*.md`, read-only) and user-created ones on disk (default
 * `data/skills/*.md`, managed through the UI via SkillController). A skill can be invoked
 * explicitly in chat as `/name argument`, or activate automatically when the user's message
 * matches one of its trigger phrases (the optional `Gatilhos:` line right after the title) — the
 * local model is unreliable at re-deriving common procedures from scratch, so matching here in
 * deterministic code beats hoping the model remembers. A custom skill with the same name as a
 * built-in one takes precedence.
 */
@Component
public class SkillRegistry {

    private static final Pattern VALID_SKILL_NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{1,63}$");

    private final Map<String, Skill> builtinSkills;
    private final Map<String, Skill> customSkills = new ConcurrentHashMap<>();
    private final Path skillsDirectory;

    public SkillRegistry() {
        this("data/skills");
    }

    @Autowired
    public SkillRegistry(@Value("${avento.agent.skills-dir:data/skills}") String skillsDirectory) {
        this.skillsDirectory = Paths.get(skillsDirectory).toAbsolutePath().normalize();
        this.builtinSkills = loadBuiltinSkills();
        loadCustomSkillsFromDisk();
    }

    public Optional<Skill> find(String name) {
        Skill custom = customSkills.get(name);
        if (custom != null) {
            return Optional.of(custom);
        }
        return Optional.ofNullable(builtinSkills.get(name));
    }

    public Set<String> names() {
        Set<String> names = new LinkedHashSet<>(builtinSkills.keySet());
        names.addAll(customSkills.keySet());
        return names;
    }

    public List<Skill> all() {
        Map<String, Skill> merged = new LinkedHashMap<>(builtinSkills);
        merged.putAll(customSkills);
        return List.copyOf(merged.values());
    }

    /**
     * Finds the first skill whose trigger phrases match the (already normalized, lowercase,
     * accent-stripped) user message. A phrase matches when every word in it appears somewhere in
     * the message — so the trigger "criar nestjs" catches "cria(r) um projeto nestjs dentro da
     * pasta back" regardless of word order or what sits in between.
     */
    public Optional<Skill> autoMatch(String normalizedMessage) {
        if (normalizedMessage == null || normalizedMessage.isBlank()) {
            return Optional.empty();
        }
        Skill bestMatch = null;
        int bestScore = -1;
        for (Skill skill : all()) {
            for (String trigger : skill.triggers()) {
                if (allWordsPresent(trigger, normalizedMessage)) {
                    int score = trigger.split("\\s+").length;
                    if (score > bestScore) {
                        bestMatch = skill;
                        bestScore = score;
                    }
                }
            }
        }
        return Optional.ofNullable(bestMatch);
    }

    public Skill saveCustomSkill(String name, String description, List<String> triggers, String body)
            throws IOException {
        if (name == null || !VALID_SKILL_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Nome de skill inválido: use só letras minúsculas, números e hífen (ex.: minha-skill).");
        }
        if (builtinSkills.containsKey(name)) {
            throw new IllegalArgumentException("A skill `" + name + "` é embutida do sistema e não pode ser alterada.");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("A descrição da skill é obrigatória.");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("O procedimento da skill é obrigatório.");
        }

        StringBuilder content =
                new StringBuilder("# ").append(description.strip()).append('\n');
        List<String> cleanTriggers = new ArrayList<>();
        if (triggers != null) {
            for (String trigger : triggers) {
                if (trigger != null && !trigger.isBlank()) {
                    String normalized = normalizeTrigger(trigger);
                    if (!normalized.isBlank() && !cleanTriggers.contains(normalized)) {
                        cleanTriggers.add(normalized);
                    }
                }
            }
        }
        if (!cleanTriggers.isEmpty()) {
            content.append("Gatilhos: ")
                    .append(String.join(", ", cleanTriggers))
                    .append('\n');
        }
        content.append('\n').append(body.strip()).append('\n');

        Files.createDirectories(skillsDirectory);
        Files.writeString(skillsDirectory.resolve(name + ".md"), content.toString(), StandardCharsets.UTF_8);

        Skill skill = parseSkill(name, content.toString().strip(), false);
        customSkills.put(name, skill);
        return skill;
    }

    public void deleteCustomSkill(String name) throws IOException {
        if (builtinSkills.containsKey(name) && !customSkills.containsKey(name)) {
            throw new IllegalArgumentException("A skill `" + name + "` é embutida do sistema e não pode ser removida.");
        }
        if (customSkills.remove(name) == null) {
            throw new IllegalArgumentException("Skill não encontrada: " + name);
        }
        Files.deleteIfExists(skillsDirectory.resolve(name + ".md"));
    }

    private boolean allWordsPresent(String triggerPhrase, String message) {
        for (String word : triggerPhrase.split("\\s+")) {
            if (!word.isBlank() && !message.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Skill> loadBuiltinSkills() {
        Map<String, Skill> loaded = new LinkedHashMap<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:agent/skills/*.md");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".md")) {
                    continue;
                }
                String name = filename.substring(0, filename.length() - ".md".length());
                String content;
                try (var inputStream = resource.getInputStream()) {
                    content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).strip();
                }
                loaded.put(name, parseSkill(name, content, true));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível carregar as skills do agente", exception);
        }
        return loaded;
    }

    private void loadCustomSkillsFromDisk() {
        if (!Files.isDirectory(skillsDirectory)) {
            return;
        }
        try (Stream<Path> files = Files.list(skillsDirectory)) {
            files.filter(file -> file.getFileName().toString().endsWith(".md")).forEach(file -> {
                String filename = file.getFileName().toString();
                String name = filename.substring(0, filename.length() - ".md".length());
                try {
                    String content =
                            Files.readString(file, StandardCharsets.UTF_8).strip();
                    customSkills.put(name, parseSkill(name, content, false));
                } catch (IOException ignored) {
                    // Uma skill ilegível não deve impedir as outras de carregar.
                }
            });
        } catch (IOException ignored) {
            // Diretório ilegível: segue só com as embutidas.
        }
    }

    private Skill parseSkill(String name, String content, boolean builtin) {
        int firstLineBreak = content.indexOf('\n');
        String firstLine = firstLineBreak == -1 ? content : content.substring(0, firstLineBreak);
        String description = firstLine.startsWith("#") ? firstLine.substring(1).strip() : firstLine.strip();
        String body = firstLineBreak == -1
                ? ""
                : content.substring(firstLineBreak + 1).strip();

        List<String> triggers = new ArrayList<>();
        String tool = "";
        List<String> tools = new ArrayList<>();
        Integer maxRounds = null;
        // Linhas de cabecalho logo apos o titulo, em qualquer ordem:
        // `Gatilhos: frase, frase`, `Ferramenta: nome_da_ferramenta`, `Ferramentas: nome, nome` e `MaxRodadas: N`.
        while (true) {
            String lowerBody = body.toLowerCase(Locale.ROOT);
            int lineBreak = body.indexOf('\n');
            String headerLine = lineBreak == -1 ? body : body.substring(0, lineBreak);
            if (lowerBody.startsWith("gatilhos:")) {
                for (String trigger : headerLine.substring("gatilhos:".length()).split(",")) {
                    String normalized = normalizeTrigger(trigger);
                    if (!normalized.isBlank() && !triggers.contains(normalized)) {
                        triggers.add(normalized);
                    }
                }
            } else if (lowerBody.startsWith("ferramenta:")) {
                tool = headerLine.substring("ferramenta:".length()).strip();
            } else if (lowerBody.startsWith("ferramentas:")) {
                for (String t : headerLine.substring("ferramentas:".length()).split(",")) {
                    String trimmed = t.strip();
                    if (!trimmed.isBlank() && !tools.contains(trimmed)) {
                        tools.add(trimmed);
                    }
                }
            } else if (lowerBody.startsWith("maxrodadas:")) {
                try {
                    maxRounds = Integer.parseInt(
                            headerLine.substring("maxrodadas:".length()).strip());
                } catch (NumberFormatException ignored) {
                }
            } else {
                break;
            }
            body = lineBreak == -1 ? "" : body.substring(lineBreak + 1).strip();
        }
        return new Skill(name, description, List.copyOf(triggers), tool, List.copyOf(tools), maxRounds, body, builtin);
    }

    private String normalizeTrigger(String trigger) {
        return Normalizer.normalize(trigger.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^\\p{L}\\p{N}\\s_-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
