package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.service.dto.ProjectCommandRequest;
import com.avento.service.dto.ProjectCommandResult;
import com.avento.service.dto.VerificationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class ProjectVerificationServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ProjectVerificationService service(Path dir, ProjectCommandService commands) {
        WorkspaceAccessService workspace = mock(WorkspaceAccessService.class);
        when(workspace.requireAuthorized(any(String.class))).thenReturn(dir);
        return new ProjectVerificationService(commands, workspace, mapper);
    }

    private ProjectCommandResult result(int exitCode, boolean timedOut, String output) {
        return new ProjectCommandResult("npm", "validate", "npm run validate", exitCode, timedOut, 1.0, output, "now");
    }

    @Test
    void detectsTheNpmValidateScriptAndReportsSuccess(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("package.json"), "{\"scripts\": {\"validate\": \"tsc\", \"lint\": \"eslint\"}}");
        ProjectCommandService commands = mock(ProjectCommandService.class);
        when(commands.run(any(ProjectCommandRequest.class))).thenReturn(result(0, false, "ok"));

        VerificationResult verification = service(dir, commands).verify(dir.toString());

        assertThat(verification.detected()).isTrue();
        assertThat(verification.ok()).isTrue();
        assertThat(verification.errorSummary()).isEmpty();

        ArgumentCaptor<ProjectCommandRequest> captor = ArgumentCaptor.forClass(ProjectCommandRequest.class);
        org.mockito.Mockito.verify(commands).run(captor.capture());
        assertThat(captor.getValue().runner()).isEqualTo("npm");
        assertThat(captor.getValue().name()).isEqualTo("validate");
    }

    @Test
    void reportsFailureWithATrimmedErrorSummary(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("package.json"), "{\"scripts\": {\"build\": \"vite build\"}}");
        ProjectCommandService commands = mock(ProjectCommandService.class);
        String log = "info a\ninfo b\nsrc/x.ts(3,1): error TS2304: Cannot find name 'foo'.\nmore info\n";
        when(commands.run(any(ProjectCommandRequest.class))).thenReturn(result(1, false, log));

        VerificationResult verification = service(dir, commands).verify(dir.toString());

        assertThat(verification.ok()).isFalse();
        assertThat(verification.errorSummary()).contains("error TS2304").doesNotContain("info a");
    }

    @Test
    void fallsBackToMavenTestWhenOnlyPomExists(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        ProjectCommandService commands = mock(ProjectCommandService.class);
        when(commands.run(any(ProjectCommandRequest.class))).thenReturn(result(0, false, ""));

        VerificationResult verification = service(dir, commands).verify(dir.toString());

        assertThat(verification.detected()).isTrue();
        ArgumentCaptor<ProjectCommandRequest> captor = ArgumentCaptor.forClass(ProjectCommandRequest.class);
        org.mockito.Mockito.verify(commands).run(captor.capture());
        assertThat(captor.getValue().runner()).isEqualTo("maven");
        assertThat(captor.getValue().name()).isEqualTo("test");
    }

    @Test
    void reportsNoCommandDetectedForABareDirectory(@TempDir Path dir) {
        ProjectCommandService commands = mock(ProjectCommandService.class);

        VerificationResult verification = service(dir, commands).verify(dir.toString());

        assertThat(verification.detected()).isFalse();
        assertThat(verification.ok()).isFalse();
        assertThat(verification.errorSummary()).contains("Nenhum comando de verificação");
    }
}
