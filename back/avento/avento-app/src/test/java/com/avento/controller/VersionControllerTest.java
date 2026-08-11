package com.avento.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.avento.controller.dto.VersionResponse;
import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

class VersionControllerTest {

    @Test
    void returnsTheVersionAndBuildTimeFromBuildMetadata() {
        Properties metadata = new Properties();
        metadata.setProperty("version", "2.0.0");
        metadata.setProperty("time", "2026-08-11T02:00:00Z");
        VersionController controller = new VersionController(new BuildProperties(metadata));

        VersionResponse response = controller.version();

        assertThat(response.version()).isEqualTo("2.0.0");
        assertThat(response.buildTime()).isEqualTo(Instant.parse("2026-08-11T02:00:00Z"));
    }
}
