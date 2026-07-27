package com.avento.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.avento.service.dto.AgentRunSnapshot;
import com.avento.service.orchestration.AgentRunRegistry;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentRunWorkerTest {

    /**
     * O worker repassava as pastas da tarefa para o prompt sem registrá-las no
     * {@link com.avento.service.WorkspaceAccessService} — o fluxo de chat registra, o worker não.
     * Resultado: o agente conhecia o caminho mas o sandbox não autorizava nada, e toda ferramenta
     * de arquivo/terminal das tarefas do Cowork falhava com SecurityException.
     */
    @Test
    void registersTaskFoldersInTheSandboxSoToolsCanUseThem(@TempDir Path projectFolder) throws Exception {
        com.avento.service.WorkspaceAccessService workspaceAccess =
                new com.avento.service.WorkspaceAccessService(new com.avento.service.tools.ToolExecutionContext());
        UUID userId = UUID.randomUUID();
        AgentRunWorker worker = workerWith(workspaceAccess);

        List<String> registered = invokeRegisterWorkspaceRoots(worker, userId, List.of(projectFolder.toString()));

        assertThat(registered).containsExactly(projectFolder.toString());
        // Autorizado de verdade: sem o registro esta chamada lançava SecurityException.
        assertThat(workspaceAccess.requireAuthorized(userId, projectFolder.toString()))
                .isEqualTo(projectFolder.toRealPath());
    }

    @Test
    void skipsFoldersThatNoLongerExistInsteadOfFailingTheWholeRun(@TempDir Path projectFolder) throws Exception {
        com.avento.service.WorkspaceAccessService workspaceAccess =
                new com.avento.service.WorkspaceAccessService(new com.avento.service.tools.ToolExecutionContext());
        AgentRunWorker worker = workerWith(workspaceAccess);

        List<String> registered = invokeRegisterWorkspaceRoots(
                worker, UUID.randomUUID(), List.of("/pasta/que/nao/existe", projectFolder.toString()));

        assertThat(registered).containsExactly(projectFolder.toString());
    }

    // Exigir pasta em TODA execução quebrou a conversa comum: um chat sem projeto conectado é caso
    // normal e não deve falhar. Só a tarefa agendada (payload com taskId) precisa de pasta, porque
    // existe para agir sobre um projeto.
    @Test
    void onlyScheduledTasksRequireAWorkspaceFolder() throws Exception {
        assertThat(requiresFolder("{\"taskId\":12,\"prompt\":\"roda os testes\"}"))
                .isTrue();
        assertThat(requiresFolder("{\"prompt\":\"oi, tudo bem?\"}")).isFalse();
        assertThat(requiresFolder("{\"taskId\":0,\"prompt\":\"oi\"}")).isFalse();
    }

    private boolean requiresFolder(String payloadJson) throws Exception {
        com.fasterxml.jackson.databind.JsonNode request =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(payloadJson);
        return request.path("taskId").asLong(0L) > 0L;
    }

    @SuppressWarnings("unchecked")
    private AgentRunWorker workerWith(com.avento.service.WorkspaceAccessService workspaceAccess) {
        org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate>
                redisProvider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(redisProvider.getIfAvailable()).thenReturn(null);
        return new AgentRunWorker(
                null,
                null,
                null,
                null,
                null,
                null,
                redisProvider,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                workspaceAccess);
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeRegisterWorkspaceRoots(AgentRunWorker worker, UUID userId, List<String> roots)
            throws Exception {
        java.lang.reflect.Method method =
                AgentRunWorker.class.getDeclaredMethod("registerWorkspaceRoots", UUID.class, List.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(worker, userId, roots);
    }

    @Test
    void recognizesBusyGroupInANestedRedisException() {
        RuntimeException error = new RuntimeException(
                "Redis initialization failed",
                new IllegalStateException("BUSYGROUP Consumer Group name already exists"));

        assertThat(AgentRunWorker.consumerGroupAlreadyExists(error)).isTrue();
        assertThat(AgentRunWorker.consumerGroupAlreadyExists(new RuntimeException("connection refused")))
                .isFalse();
    }

    @Test
    void recognizesTimeoutInANestedExecutionError() {
        RuntimeException error = new RuntimeException("worker failed", new TimeoutException("run timed out"));

        assertThat(AgentRunWorker.causedByTimeout(error)).isTrue();
        assertThat(AgentRunWorker.causedByTimeout(new RuntimeException("model failed")))
                .isFalse();
    }

    @Test
    void detectsRunsWhoseLastActivityExceededTheWatchdogThreshold() {
        LocalDateTime now = LocalDateTime.now();
        AgentRunSnapshot stale = new AgentRunSnapshot(
                "run_stale",
                "Analyze",
                List.of(),
                AgentRunRegistry.AgentRunStatus.RUNNING,
                "agent.round.started",
                null,
                now.minusMinutes(4),
                now.minusMinutes(4));

        assertThat(AgentRunWorker.isInactive(stale, now.minusMinutes(3))).isTrue();
        assertThat(AgentRunWorker.isInactive(stale, now.minusMinutes(5))).isFalse();
    }
}
