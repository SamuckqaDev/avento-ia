package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.avento.service.event.WorkspaceRootRegisteredEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Registering a workspace has to say so out loud.
 *
 * <p>The vector index was built only by an explicit REST call, so for the agent it was always empty
 * — {@code search_code} could never do more than literal matching. Announcing the root is what lets
 * the indexer start on its own.
 */
class WorkspaceRootRegistrationEventTest {

    @TempDir
    Path tempDir;

    private final List<WorkspaceRootRegisteredEvent> published = new ArrayList<>();

    private final ApplicationEventPublisher publisher = event -> {
        if (event instanceof WorkspaceRootRegisteredEvent registered) {
            published.add(registered);
        }
    };

    @Test
    void announcesEachNewWorkspaceRoot() throws Exception {
        WorkspaceAccessService service = serviceWithPublisher();
        Path workspace = Files.createDirectory(tempDir.resolve("projeto"));

        service.registerWorkspaceRoot(workspace.toString());

        assertThat(published).hasSize(1);
        assertThat(published.get(0).root()).isEqualTo(workspace.toRealPath());
    }

    /**
     * The same folder is registered again on every message of a conversation. Announcing it each time
     * would turn "index this project once" into "rescan this project per message".
     */
    @Test
    void staysQuietWhenTheSameRootIsRegisteredAgain() throws Exception {
        WorkspaceAccessService service = serviceWithPublisher();
        Path workspace = Files.createDirectory(tempDir.resolve("projeto"));

        service.registerWorkspaceRoot(workspace.toString());
        service.registerWorkspaceRoot(workspace.toString());
        service.registerWorkspaceRoot(workspace.toString());

        assertThat(published).hasSize(1);
    }

    @Test
    void announcesTheSameFolderOncePerUserScope() throws Exception {
        WorkspaceAccessService service = serviceWithPublisher();
        Path workspace = Files.createDirectory(tempDir.resolve("projeto"));

        service.registerWorkspaceRoot(UUID.randomUUID(), workspace.toString());
        service.registerWorkspaceRoot(UUID.randomUUID(), workspace.toString());

        assertThat(published).hasSize(2);
    }

    /** Most callers build this service without a publisher; that must stay a no-op, not a crash. */
    @Test
    void worksWithoutAPublisher() throws Exception {
        WorkspaceAccessService service = new WorkspaceAccessService();
        Path workspace = Files.createDirectory(tempDir.resolve("projeto"));

        assertThat(service.registerWorkspaceRoot(workspace.toString())).isEqualTo(workspace.toRealPath());
    }

    private WorkspaceAccessService serviceWithPublisher() {
        WorkspaceAccessService service = new WorkspaceAccessService();
        service.setEventPublisher(publisher);
        return service;
    }
}
