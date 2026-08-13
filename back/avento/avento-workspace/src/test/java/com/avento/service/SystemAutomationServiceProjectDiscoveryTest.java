package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemAutomationServiceProjectDiscoveryTest {

    @TempDir
    Path userHome;

    private final SystemAutomationService service = new SystemAutomationService();

    @Test
    void findsProjectFoldersCaseInsensitivelyWithoutEnteringHeavyDirectories() throws Exception {
        Path expected = Files.createDirectories(userHome.resolve("projetos/Monicare"));
        Files.createDirectories(userHome.resolve("projetos/monicare-api"));
        Files.createDirectories(userHome.resolve("node_modules/Monicare"));
        Files.createDirectories(userHome.resolve("Library/Monicare"));

        var result = service.findLocalProjects("MONICARE", userHome);

        assertThat(result.query()).isEqualTo("MONICARE");
        assertThat(result.matches()).extracting(match -> match.path())
                .contains(expected.toString(), userHome.resolve("projetos/monicare-api").toString())
                .doesNotContain(userHome.resolve("node_modules/Monicare").toString(), userHome.resolve("Library/Monicare").toString());
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void rejectsAnEmptyProjectName() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> service.findLocalProjects(" ", userHome))
                .withMessage("Project name is required");
    }
}
