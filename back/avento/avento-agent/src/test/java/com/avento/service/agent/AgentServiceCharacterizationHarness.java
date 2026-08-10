package com.avento.service.agent;

import com.avento.controller.McpController;
import com.avento.dto.MacApplication;
import com.avento.service.SystemAutomationService;
import com.avento.service.intent.ImageIntentService;
import com.avento.service.intent.IntentEmbeddingClassifier;
import com.avento.service.intent.IntentRouter;
import com.avento.service.intent.VisualIntentClassifier;
import com.avento.service.memory.MemoryExtractionService;
import com.avento.service.memory.UserMemoryService;
import com.avento.service.prompt.PromptAssemblyService;
import com.avento.service.settings.TokenUsageService;
import com.avento.service.settings.UserSettingsService;
import com.avento.service.support.SkillRegistry;
import com.avento.service.tools.ToolCapabilityRegistry;
import com.avento.service.tools.ToolExecutionContext;
import com.avento.service.tools.ToolExecutionGateway;
import com.avento.service.tools.ToolResultVerifier;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Base compartilhada dos testes de CARACTERIZAÇÃO do miolo do {@link AgentService}.
 *
 * <p><b>Caracterização, não validação.</b> Os testes que herdam desta classe fixam o comportamento
 * ATUAL — inclusive o que parece errado. Se um assert aqui parecer descrever um defeito, ele
 * provavelmente descreve mesmo: o objetivo é detectar MUDANÇA acidental, não afirmar que o
 * comportamento é o desejado. Antes de "corrigir" um assert, confira se o código de produção mudou
 * de propósito.
 *
 * <p><b>Por que esta classe existe:</b> {@code runTurn}, {@code selectToolsForCurrentRequest} e
 * {@code finishTurn} somam 412 linhas e decidem todo o comportamento do agente, e até 08/08/2026
 * tinham zero cobertura — os 727 testes da suíte cobriam só as bordas. Dois consertos da sessão de
 * 02–08/08 geraram defeito novo por falta exatamente desta rede.
 *
 * <p><b>Por que reflexão em vez de tornar os métodos públicos:</b> a rede tem de existir ANTES de
 * qualquer mudança no código de produção. Mudar visibilidade para testar já é a mudança que a rede
 * deveria estar cobrindo. A extração em classes menores é trabalho posterior, e depende disto.
 *
 * <p><b>Cuidados que já morderam:</b>
 *
 * <ul>
 *   <li>{@code ollamaBaseUrl} aponta para uma porta fechada de propósito. {@code finishTurn} reentra
 *       em {@code runTurn} em quatro pontos; sem isso um teste dispararia rodada de verdade.
 *   <li>O {@link IntentEmbeddingClassifier} é construído com cliente {@code null} porque
 *       {@code selectToolsForCurrentRequest} chama {@code intentRouter.classify()}, que faria uma
 *       chamada de embedding SÍNCRONA. Teste pendurado costuma ser isto.
 * </ul>
 */
abstract class AgentServiceCharacterizationHarness {

    protected final ObjectMapper mapper = new ObjectMapper();
    protected final McpController mcpController = new McpController();
    protected final ToolCapabilityRegistry toolRegistry = new ToolCapabilityRegistry();

    private final SystemAutomationService systemAutomationService = new SystemAutomationService() {
        @Override
        public List<MacApplication> listMacApplications() {
            return List.of(new MacApplication("Google Chrome", "/Applications/Google Chrome.app"));
        }
    };

    protected final AgentPermissionService permissionService = new AgentPermissionService(Optional.empty());
    protected final AgentTimelineService timelineService = new AgentTimelineService(Optional.empty());
    private final SkillRegistry skillRegistry = new SkillRegistry();
    private final ToolExecutionGateway toolGateway =
            new ToolExecutionGateway(mcpController, new ToolResultVerifier(mapper), new ToolExecutionContext());
    private final TokenUsageService tokenUsageService = org.mockito.Mockito.mock(TokenUsageService.class);
    private final UserSettingsService userSettingsService = org.mockito.Mockito.mock(UserSettingsService.class);
    private final UserMemoryService userMemoryService = org.mockito.Mockito.mock(UserMemoryService.class);
    private final MemoryExtractionService memoryExtractionService =
            org.mockito.Mockito.mock(MemoryExtractionService.class);
    private final PendingToolApprovalService pendingApprovalService =
            org.mockito.Mockito.mock(PendingToolApprovalService.class);

    private PromptAssemblyService promptAssembly() {
        PromptAssemblyService service = new PromptAssemblyService();
        service.setUserMemoryService(userMemoryService);
        service.setPolicyMode("maximum");
        service.setPolicyOverrideDirectory("");
        return service;
    }

    /**
     * Os literais abaixo são copiados de {@code AgentServiceDirectAutomationTest} e fazem PARTE do
     * comportamento caracterizado. Trocar {@code maxToolRounds} (6), {@code maxProjectTools} (18) ou
     * o {@code projectToolkit} por valores "mais redondos" invalida os testes que dependem deles.
     */
    protected final AgentService service = new AgentService(
            toolGateway,
            toolRegistry,
            new IntentRouter(toolRegistry, new IntentEmbeddingClassifier(null, 0.55, 2000), true),
            systemAutomationService,
            permissionService,
            timelineService,
            skillRegistry,
            tokenUsageService,
            userSettingsService,
            promptAssembly(),
            memoryExtractionService,
            pendingApprovalService,
            new ImageIntentService(new VisualIntentClassifier()),
            mapper,
            // Porta fechada de proposito: ver o javadoc da classe.
            "http://localhost:9",
            6,
            16,
            16384,
            4096,
            0.15,
            0.9,
            30,
            1.08,
            true,
            "30m",
            12,
            18,
            false,
            "directory_tree,read_file,write_file,edit_file,delete_file,terminal_run",
            10,
            6000,
            4000,
            14000,
            "qwen3:8b",
            "qwen3,qwen3.5,gemma4,deepseek",
            "qwen2.5vl:7b");

    /** O kit fixo passado ao construtor acima, na mesma ordem. */
    protected static final List<String> PROJECT_TOOLKIT =
            List.of("directory_tree", "read_file", "write_file", "edit_file", "delete_file", "terminal_run");

    protected static final String STATE_CLASS = "com.avento.service.agent.AgentService$AgentRunState";
    protected static final String CAPTURE_CLASS = "com.avento.service.agent.AgentService$TurnCapture";

    // ── Construção das classes internas ────────────────────────────────────────────────────────

    protected Class<?> stateClass() throws Exception {
        return Class.forName(STATE_CLASS);
    }

    protected Class<?> captureClass() throws Exception {
        return Class.forName(CAPTURE_CLASS);
    }

    protected Object newRunState() throws Exception {
        Constructor<?> constructor = stateClass().getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    protected Object newCapture() throws Exception {
        Constructor<?> constructor = captureClass().getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    /** Escreve um campo de {@code AgentRunState} ou {@code TurnCapture} (todos package-private). */
    protected void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    protected Object get(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    /** Acrescenta texto ao {@code assistantText} do capture, que é um {@link StringBuilder}. */
    protected void appendAssistantText(Object capture, String text) throws Exception {
        ((StringBuilder) get(capture, "assistantText")).append(text);
    }

    // ── Invocação dos métodos privados ─────────────────────────────────────────────────────────

    protected Method privateMethod(String name, Class<?>... signature) throws Exception {
        Method method = AgentService.class.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        return method;
    }

    // ── Fixtures de mensagem e ferramenta ──────────────────────────────────────────────────────

    protected ArrayNode userMessages(String... contents) {
        ArrayNode messages = mapper.createArrayNode();
        for (String content : contents) {
            ObjectNode message = messages.addObject();
            message.put("role", "user");
            message.put("content", content);
        }
        return messages;
    }

    /** Catálogo de ferramentas no formato que a seleção espera: objetos com {@code name}. */
    protected ArrayNode toolsNamed(String... names) {
        ArrayNode tools = mapper.createArrayNode();
        for (String name : names) {
            tools.addObject().put("name", name);
        }
        return tools;
    }

    protected ArrayNode toolsNamed(List<String> names) {
        return toolsNamed(names.toArray(new String[0]));
    }

    /** Nomes presentes num ArrayNode de ferramentas, para assert legível. */
    protected List<String> namesOf(ArrayNode tools) {
        return java.util.stream.StreamSupport.stream(tools.spliterator(), false)
                .map(tool -> tool.path("name").asText(""))
                .toList();
    }
}
