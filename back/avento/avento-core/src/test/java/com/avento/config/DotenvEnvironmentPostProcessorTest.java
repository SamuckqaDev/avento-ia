package com.avento.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DotenvEnvironmentPostProcessorTest {

    private final DotenvEnvironmentPostProcessor processor = new DotenvEnvironmentPostProcessor();

    private Path originalDir;

    @AfterEach
    void restoreWorkingDirectory() {
        if (originalDir != null) {
            System.setProperty("user.dir", originalDir.toString());
        }
    }

    @Test
    void loadsValuesFromAnEnvFileInTheWorkingDirectory() throws IOException {
        Path workDir = withEnvFile(List.of(
                "# comment line",
                "",
                "AVENTO_AUTH_ROOT_PASSWORD=super-secret-value",
                "AVENTO_AUTH_ROOT_EMAIL=\"quoted@example.com\""));

        MockEnvironment environment = new MockEnvironment();
        processor.postProcessEnvironment(environment, null);

        assertEquals("super-secret-value", environment.getProperty("AVENTO_AUTH_ROOT_PASSWORD"));
        assertEquals("quoted@example.com", environment.getProperty("AVENTO_AUTH_ROOT_EMAIL"));
    }

    @Test
    void realOsEnvironmentVariablesTakePriorityOverTheDotenvFile() throws IOException {
        withEnvFile(List.of("AVENTO_AUTH_ROOT_PASSWORD=from-dotenv-file"));

        MockEnvironment environment = new MockEnvironment();
        environment
                .getPropertySources()
                .addFirst(new org.springframework.core.env.MapPropertySource(
                        "systemEnvironment", java.util.Map.of("AVENTO_AUTH_ROOT_PASSWORD", "from-real-env")));

        processor.postProcessEnvironment(environment, null);

        assertEquals("from-real-env", environment.getProperty("AVENTO_AUTH_ROOT_PASSWORD"));
    }

    @Test
    void doesNothingWhenNoEnvFileExists(@org.junit.jupiter.api.io.TempDir Path tempDir) {
        originalDir = Path.of(System.getProperty("user.dir"));
        System.setProperty("user.dir", tempDir.toString());

        MockEnvironment environment = new MockEnvironment();
        processor.postProcessEnvironment(environment, null);

        assertNull(environment.getProperty("AVENTO_AUTH_ROOT_PASSWORD"));
    }

    private Path withEnvFile(List<String> lines) throws IOException {
        Path tempDir = Files.createTempDirectory("dotenv-test");
        Files.write(tempDir.resolve(".env"), lines);
        originalDir = Path.of(System.getProperty("user.dir"));
        System.setProperty("user.dir", tempDir.toString());
        return tempDir;
    }
}
