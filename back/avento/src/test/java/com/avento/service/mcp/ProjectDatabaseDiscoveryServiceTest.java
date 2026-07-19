package com.avento.service.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.service.dto.DatabaseConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectDatabaseDiscoveryServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void discoversDatabaseUrlWithoutWritingTheCredentialToToml() throws Exception {
        Path project = Files.createDirectory(tempDirectory.resolve("project"));
        Files.writeString(
                project.resolve(".env"),
                "DATABASE_URL=postgres://project_user:secret@127.0.0.1:5432/project_db?sslmode=disable\n",
                StandardCharsets.UTF_8);
        ProjectDatabaseDiscoveryService service = service();

        DatabaseConfiguration configuration =
                service.discover(List.of(project.toString())).orElseThrow();

        assertEquals("DATABASE_URL", configuration.source());
        assertTrue(configuration
                .environment()
                .get(ProjectDatabaseDiscoveryService.PROJECT_DSN_ENV)
                .contains("project_db"));
        assertFalse(Files.readString(configuration.configFile()).contains("secret"));
        assertTrue(Files.readString(configuration.configFile()).contains("${AVENTO_PROJECT_DATABASE_DSN}"));
    }

    @Test
    void discoversPublishedPostgresFromDockerCompose() throws Exception {
        Path project = Files.createDirectory(tempDirectory.resolve("docker-project"));
        Files.writeString(project.resolve("compose.yml"), """
                services:
                  database:
                    image: postgres:16
                    ports:
                      - "5544:5432"
                    environment:
                      POSTGRES_USER: app
                      POSTGRES_PASSWORD: local-secret
                      POSTGRES_DB: app_db
                """, StandardCharsets.UTF_8);

        DatabaseConfiguration configuration =
                service().discover(List.of(project.toString())).orElseThrow();
        String dsn = configuration.environment().get(ProjectDatabaseDiscoveryService.PROJECT_DSN_ENV);

        assertEquals("compose.yml (servico database)", configuration.source());
        assertTrue(dsn.startsWith("postgres://app:local-secret@127.0.0.1:5544/app_db"));
    }

    @Test
    void usesProjectDbhubConfigBeforeAutomaticDiscovery() throws Exception {
        Path project = Files.createDirectory(tempDirectory.resolve("configured-project"));
        Path dbhubConfig = project.resolve("dbhub.toml");
        Files.writeString(
                dbhubConfig,
                "[[sources]]\nid = \"custom\"\ndsn = \"sqlite:///tmp/custom.db\"\n",
                StandardCharsets.UTF_8);
        Files.writeString(project.resolve(".env"), "DATABASE_URL=postgres://ignored@localhost/ignored\n");

        DatabaseConfiguration configuration =
                service().discover(List.of(project.toString())).orElseThrow();

        assertEquals(dbhubConfig.toRealPath(), configuration.configFile());
        assertEquals("dbhub.toml", configuration.source());
        assertTrue(configuration.environment().isEmpty());
    }

    @Test
    void discoversKnownSqliteFileInsideWorkspace() throws Exception {
        Path project = Files.createDirectory(tempDirectory.resolve("sqlite-project"));
        Path database = Files.write(project.resolve("dev.db"), new byte[] {0, 1, 2});

        DatabaseConfiguration configuration =
                service().discover(List.of(project.toString())).orElseThrow();
        String dsn = configuration.environment().get(ProjectDatabaseDiscoveryService.PROJECT_DSN_ENV);

        assertEquals("dev.db", configuration.source());
        assertEquals("sqlite://" + database.toRealPath().toUri().getPath(), dsn);
    }

    private ProjectDatabaseDiscoveryService service() {
        return new ProjectDatabaseDiscoveryService(tempDirectory.resolve("generated"));
    }
}
