package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.model.PendingToolApproval;
import com.avento.repository.PendingToolApprovalRepository;
import com.avento.service.dto.PendingToolExecution;
import com.avento.service.dto.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PendingToolApprovalServiceTest {

    @Test
    void persistsAndRecoversAnApprovalAfterProcessMemoryIsLost() {
        PendingToolApprovalRepository repository = mock(PendingToolApprovalRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<PendingToolApproval> stored = new AtomicReference<>();
        when(repository.save(any())).thenAnswer(invocation -> {
            PendingToolApproval approval = invocation.getArgument(0);
            stored.set(approval);
            return approval;
        });
        when(repository.findByApprovalId("approval_1")).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        PendingToolApprovalService service = new PendingToolApprovalService(repository, mapper);
        UUID userId = UUID.randomUUID();
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("path", "/tmp/project/App.java");
        arguments.put("_userId", userId.toString());
        arguments.put("_chatId", 9L);
        var messages = mapper.createArrayNode();
        messages.addObject().put("role", "user").put("content", "edite");
        PendingToolExecution execution = new PendingToolExecution(
                "qwen3:8b",
                messages,
                0,
                1,
                new ToolCall("call_1", "edit_file", arguments),
                true,
                List.of("/tmp/project"),
                "run_1");

        service.save("approval_1", execution);
        Optional<PendingToolExecution> recovered = service.resolve("approval_1");

        assertThat(recovered).isPresent();
        assertThat(recovered.orElseThrow().toolCall().name()).isEqualTo("edit_file");
        assertThat(recovered.orElseThrow().runId()).isEqualTo("run_1");
        assertThat(stored.get().getStatus()).isEqualTo(PendingToolApproval.STATUS_RESOLVED);
    }

    @Test
    void ownershipLookupDoesNotExposeAnotherUsersApproval() {
        PendingToolApprovalRepository repository = mock(PendingToolApprovalRepository.class);
        PendingToolApprovalService service = new PendingToolApprovalService(repository, new ObjectMapper());
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        PendingToolApproval approval = new PendingToolApproval();
        approval.setUserId(owner);
        when(repository.findByApprovalIdAndUserIdAndStatus("approval_2", owner, PendingToolApproval.STATUS_PENDING))
                .thenReturn(Optional.of(approval));

        assertThat(service.isOwnedPending("approval_2", owner)).isTrue();
        assertThat(service.isOwnedPending("approval_2", other)).isFalse();
    }
}
