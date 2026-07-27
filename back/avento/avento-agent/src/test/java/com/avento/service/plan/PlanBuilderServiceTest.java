package com.avento.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.api.dto.PlanCreateRequest;
import com.avento.model.AgentPlan;
import com.avento.model.AgentTask;
import com.avento.model.Chat;
import com.avento.repository.AgentPlanRepository;
import com.avento.repository.AgentTaskRepository;
import com.avento.repository.ChatRepository;
import com.avento.service.AgentService;
import com.avento.service.WorkspaceAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

class PlanBuilderServiceTest {

    private final AgentPlanRepository planRepository = mock(AgentPlanRepository.class);
    private final AgentTaskRepository taskRepository = mock(AgentTaskRepository.class);
    private final ChatRepository chatRepository = mock(ChatRepository.class);
    private final AgentService agentService = mock(AgentService.class);
    private final WorkspaceAccessService workspaceAccessService = mock(WorkspaceAccessService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentRoutingService agentRoutingService = mock(AgentRoutingService.class);
    private final UUID userId = UUID.randomUUID();
    private final Long chatId = 123L;

    @TempDir
    Path workspace;

    private PlanBuilderService service;

    @BeforeEach
    void setup() {
        service = new PlanBuilderService(
                planRepository,
                taskRepository,
                chatRepository,
                agentService,
                workspaceAccessService,
                mapper,
                agentRoutingService);
        com.avento.model.AgentProfile routedAgent =
                new com.avento.model.AgentProfile(userId, "Generalista", "", "", "", "", null, true);
        routedAgent.setId(1L);
        when(agentRoutingService.pick(any(), any())).thenReturn(new AgentRoutingService.Routed(routedAgent, "default"));
        AgentPlan saved = new AgentPlan(userId, chatId, "goal", AgentPlan.STATUS_DRAFT, "[]");
        saved.setId(1L);
        when(planRepository.save(any(AgentPlan.class))).thenReturn(saved);
        when(chatRepository.findByIdAndUserId(chatId, userId)).thenReturn(Optional.of(new Chat()));
        when(workspaceAccessService.requireAuthorized(userId, workspace.toString()))
                .thenReturn(workspace);
        when(taskRepository.save(any(AgentTask.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void parsesStructuredTasksAndNormalizesTargetFiles() {
        when(agentService.completeTextOnly(anyString(), any(), anyInt()))
                .thenReturn(Mono.just("prefix {\"tasks\":[{\"title\":\"Task 1\",\"details\":\"Do it\","
                        + "\"targetFiles\":[\"src/app.ts\",\"src/app.ts\"],\"needsApproval\":false}]} suffix"));

        service.buildPlan(userId, request());

        ArgumentCaptor<AgentTask> task = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskRepository).save(task.capture());
        assertThat(task.getValue().getUserId()).isEqualTo(userId);
        assertThat(task.getValue().getOrderIndex()).isEqualTo(1);
        assertThat(task.getValue().getTargetFiles()).isEqualTo("[\"src/app.ts\"]");
    }

    @Test
    void limitsModelOutputToTwentyTasks() throws Exception {
        List<Map<String, Object>> tasks = IntStream.rangeClosed(1, 25)
                .mapToObj(index -> Map.of(
                        "title",
                        "Task " + index,
                        "details",
                        "Details " + index,
                        "targetFiles",
                        List.of(),
                        "needsApproval",
                        false))
                .toList();
        when(agentService.completeTextOnly(anyString(), any(), anyInt()))
                .thenReturn(Mono.just(mapper.writeValueAsString(Map.of("tasks", tasks))));

        service.buildPlan(userId, request());

        verify(taskRepository, times(20)).save(any(AgentTask.class));
    }

    @Test
    void createsFallbackTaskWhenModelReturnsGarbage() {
        when(agentService.completeTextOnly(anyString(), any(), anyInt())).thenReturn(Mono.just("not-json"));

        service.buildPlan(userId, request());

        ArgumentCaptor<AgentTask> task = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskRepository).save(task.capture());
        assertThat(task.getValue().isNeedsApproval()).isTrue();
        assertThat(task.getValue().getDetails()).isEqualTo("Build a login screen");
    }

    private PlanCreateRequest request() {
        return new PlanCreateRequest("Build a login screen", List.of(workspace.toString()), chatId);
    }
}
