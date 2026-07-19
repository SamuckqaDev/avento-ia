package com.avento.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Loads a `.env` file from the working directory into the Spring Environment, so secrets like
 * AVENTO_AUTH_ROOT_PASSWORD never need to live in application.yml. Values already present in the
 * real OS environment always win over the file.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SOURCE_NAME = "dotenvFile";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path dotenvPath = Path.of(System.getProperty("user.dir"), ".env");
        if (!Files.isRegularFile(dotenvPath)) {
            return;
        }

        Map<String, Object> values = parse(dotenvPath);
        if (values.isEmpty()) {
            return;
        }

        MapPropertySource dotenvSource = new MapPropertySource(SOURCE_NAME, values);
        if (environment.getPropertySources().contains("systemEnvironment")) {
            environment.getPropertySources().addAfter("systemEnvironment", dotenvSource);
        } else {
            environment.getPropertySources().addLast(dotenvSource);
        }
    }

    private Map<String, Object> parse(Path dotenvPath) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(dotenvPath);
        } catch (IOException e) {
            return values;
        }

        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = trimmed.substring(0, separator).strip();
            String value = stripQuotes(trimmed.substring(separator + 1).strip());
            values.put(key, value);
        }
        return values;
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
