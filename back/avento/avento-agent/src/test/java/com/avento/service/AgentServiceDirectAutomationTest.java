package com.avento.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.controller.McpController;
import com.avento.service.dto.MacApplication;
import com.avento.service.image.ImageGenerationOptions;
import com.avento.service.intent.ImageIntentService;
import com.avento.service.intent.IntentEmbeddingClassifier;
import com.avento.service.intent.IntentProfile;
import com.avento.service.intent.IntentRouter;
import com.avento.service.intent.VisualIntentClassifier;
import com.avento.service.support.MessageText;
import com.avento.service.support.ModelNames;
import com.avento.service.support.SkillRegistry;
import com.avento.service.tools.ToolCapabilityRegistry;
import com.avento.service.tools.ToolExecutionContext;
import com.avento.service.tools.ToolExecutionGateway;
import com.avento.service.tools.ToolResultVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

class AgentServiceDirectAutomationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final McpController mcpController = new McpController();
    private final ToolCapabilityRegistry toolRegistry = new ToolCapabilityRegistry();
    private final SystemAutomationService systemAutomationService = new SystemAutomationService() {
        @Override
        public List<MacApplication> listMacApplications() {
            return List.of(
                    new MacApplication("Antigravity", "/Applications/Antigravity.app"),
                    new MacApplication("Antigravity IDE", "/Applications/Antigravity IDE.app"),
                    new MacApplication("Google Chrome", "/Applications/Google Chrome.app"),
                    new MacApplication("Visual Studio Code", "/Applications/Visual Studio Code.app"));
        }
    };
    private final AgentPermissionService permissionService = new AgentPermissionService(Optional.empty());
    private final AgentTimelineService timelineService = new AgentTimelineService(Optional.empty());
    private final SkillRegistry skillRegistry = new SkillRegistry();
    private final ToolExecutionGateway toolGateway =
            new ToolExecutionGateway(mcpController, new ToolResultVerifier(mapper), new ToolExecutionContext());
    private final TokenUsageService tokenUsageService = org.mockito.Mockito.mock(TokenUsageService.class);
    private final UserSettingsService userSettingsService = org.mockito.Mockito.mock(UserSettingsService.class);
    private final UserMemoryService userMemoryService = org.mockito.Mockito.mock(UserMemoryService.class);
    private final PromptAssemblyService promptAssemblyService = promptAssembly();

    private PromptAssemblyService promptAssembly() {
        PromptAssemblyService service = new PromptAssemblyService();
        service.setUserMemoryService(userMemoryService);
        service.setPolicyMode("maximum");
        service.setPolicyOverrideDirectory("");
        return service;
    }

    private final MemoryExtractionService memoryExtractionService =
            org.mockito.Mockito.mock(MemoryExtractionService.class);
    private final PendingToolApprovalService pendingApprovalService =
            org.mockito.Mockito.mock(PendingToolApprovalService.class);
    private final AgentService service = new AgentService(
            toolGateway,
            toolRegistry,
            new IntentRouter(toolRegistry, new IntentEmbeddingClassifier(null, 0.55, 2000), true),
            systemAutomationService,
            permissionService,
            timelineService,
            skillRegistry,
            tokenUsageService,
            userSettingsService,
            promptAssemblyService,
            memoryExtractionService,
            pendingApprovalService,
            new ImageIntentService(new VisualIntentClassifier()),
            mapper,
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
            false,
            "directory_tree,read_file,write_file,edit_file,delete_file,terminal_run",
            10,
            6000,
            4000,
            14000,
            "qwen3:8b",
            "qwen3,qwen3.5,gemma4,deepseek",
            "qwen2.5vl:7b");

    @Test
    void streamChatRoutesIdentityInputToTheModel() throws Exception {
        String directResponse = detectDirectConversationResponse("Avento. Explica para o meu amigo quem é você?");

        assertNull(directResponse);
    }

    @Test
    void interfaceMockupActivatesPrototypeSkillBeforeTheImageShortcut() {
        ArrayNode messages = userMessages("crie um mockup para a tela de login do projeto");

        String firstChunk = service.streamChat("qwen3:8b", messages).blockFirst(Duration.ofSeconds(2));

        assertNotNull(firstChunk);
        assertTrue(firstChunk.contains("skill.activated"));
        assertTrue(firstChunk.contains("prototype-interface"));
        assertFalse(firstChunk.contains("generate_image"));
    }

    @Test
    void backendPrependsIdentityGuardBeforeModelRequest() throws Exception {
        ArrayNode messages = userMessages("Explica pra mim quem é você.");

        Method method = AgentService.class.getDeclaredMethod("withBackendIdentityPrompt", ArrayNode.class, List.class);
        method.setAccessible(true);
        ArrayNode guardedMessages = (ArrayNode) method.invoke(service, messages, List.of());

        assertEquals("system", guardedMessages.get(0).path("role").asText());
        org.assertj.core.api.Assertions.assertThat(
                        guardedMessages.get(0).path("content").asText())
                .contains("Your name is Avento")
                .contains("Samuel Tomimatu")
                .contains("software engineer")
                .contains("# Avento personality")
                .contains("# Verified product facts")
                .contains("Avento's sole creator");
    }

    @Test
    void withBackendIdentityPromptTellsTheModelTheRealAuthorizedWorkspacePath() throws Exception {
        ArrayNode messages = userMessages("Edita o arquivo App.tsx pra mim.");

        Method method = AgentService.class.getDeclaredMethod("withBackendIdentityPrompt", ArrayNode.class, List.class);
        method.setAccessible(true);
        ArrayNode guardedMessages = (ArrayNode) method.invoke(service, messages, List.of("/private/tmp/real-project"));

        org.assertj.core.api.Assertions.assertThat(
                        guardedMessages.get(0).path("content").asText())
                .contains("[Workspace Roots]")
                .contains("/private/tmp/real-project")
                .contains("NUNCA invente");
    }

    @Test
    void keepsAnExtractiveSummaryOfTurnsThatFallOutOfTheModelWindow() throws Exception {
        // 14 turnos > a janela de 10: os mais antigos saem do prompt, mas viram resumo (o
        // histórico completo continua no banco). maxModelMessages do construtor de teste = 10.
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject().put("role", "user").put("content", "PRIMEIRO PEDIDO: criar a tela de login do app");
        messages.addObject().put("role", "assistant").put("content", "Beleza, vou montar a tela de login.");
        for (int turn = 2; turn < 14; turn++) {
            String role = turn % 2 == 0 ? "user" : "assistant";
            messages.addObject().put("role", role).put("content", "mensagem intermediaria numero " + turn);
        }

        Method method = AgentService.class.getDeclaredMethod("withBackendIdentityPrompt", ArrayNode.class, List.class);
        method.setAccessible(true);
        ArrayNode guardedMessages = (ArrayNode) method.invoke(service, messages, List.of());

        // [0] = prompt de identidade; [1] = resumo extrativo dos turnos que sairam da janela.
        assertEquals("system", guardedMessages.get(1).path("role").asText());
        org.assertj.core.api.Assertions.assertThat(
                        guardedMessages.get(1).path("content").asText())
                .contains("Resumo da conversa anterior")
                .contains("preservado no banco")
                .contains("PRIMEIRO PEDIDO");
    }

    @Test
    void missingWorkspaceDoesNotPolluteAnOrdinaryConversation() throws Exception {
        Method method = AgentService.class.getDeclaredMethod("withBackendIdentityPrompt", ArrayNode.class, List.class);
        method.setAccessible(true);

        ArrayNode guardedMessages =
                (ArrayNode) method.invoke(service, userMessages("Responda somente: teste concluído."), List.of());

        org.assertj.core.api.Assertions.assertThat(
                        guardedMessages.get(0).path("content").asText())
                .doesNotContain("Nenhum workspace autorizado nesta conversa ainda")
                .doesNotContain("se precisar mexer em arquivos, avise o usuário");
    }

    @Test
    void injectsProductFactsOnlyWhenTheConversationIsAboutAvento() throws Exception {
        Method method = AgentService.class.getDeclaredMethod("withBackendIdentityPrompt", ArrayNode.class, List.class);
        method.setAccessible(true);

        ArrayNode ordinary =
                (ArrayNode) method.invoke(service, userMessages("Corrija o logout do projeto."), List.of());
        ArrayNode aboutAvento = (ArrayNode) method.invoke(
                service,
                userMessages(
                        "Crie um post apresentando o Avento.", "Deixe o post mais abrangente e explique os serviços."),
                List.of());

        org.assertj.core.api.Assertions.assertThat(
                        ordinary.get(0).path("content").asText())
                .contains("# Avento personality")
                .doesNotContain("# Verified product facts");
        org.assertj.core.api.Assertions.assertThat(
                        aboutAvento.get(0).path("content").asText())
                .contains("# Verified product facts")
                .contains("ComfyUI")
                .contains("Whisper.cpp");
    }

    @Test
    void backendDropsFrontendSystemPromptAndCompactsLongHistoryBeforeModelRequest() throws Exception {
        ArrayNode messages = mapper.createArrayNode();
        ObjectNode frontendSystem = messages.addObject();
        frontendSystem.put("role", "system");
        frontendSystem.put("content", "FRONTEND SYSTEM ".repeat(1000));
        for (int index = 0; index < 20; index++) {
            ObjectNode userMessage = messages.addObject();
            userMessage.put("role", "user");
            userMessage.put("content", "Mensagem " + index + " " + "contexto ".repeat(1000));
        }

        Method method = AgentService.class.getDeclaredMethod("withBackendIdentityPrompt", ArrayNode.class, List.class);
        method.setAccessible(true);
        ArrayNode guardedMessages = (ArrayNode) method.invoke(service, messages, List.of());

        assertEquals("system", guardedMessages.get(0).path("role").asText());
        assertTrue(guardedMessages.size() <= 13);
        org.assertj.core.api.Assertions.assertThat(guardedMessages.toString())
                .doesNotContain("FRONTEND SYSTEM")
                .contains("Mensagem 19");
    }

    @Test
    void preservesTheEntireCurrentRequestWhenSupplementalContextIsCompacted() throws Exception {
        String currentRequest =
                "Analise a autenticação, preserve o refresh token e corrija o logout sem alterar o login.";
        String content = "[Project Analysis]\n" + "contexto extenso ".repeat(900)
                + "\n\nCom base no contexto local acima, responda ao seguinte pedido do usuário:\n\n"
                + currentRequest;
        ArrayNode messages = userMessages(content);

        Method method = AgentService.class.getDeclaredMethod("withBackendIdentityPrompt", ArrayNode.class, List.class);
        method.setAccessible(true);
        ArrayNode guardedMessages = (ArrayNode) method.invoke(service, messages, List.of());

        String compactedRequest = guardedMessages.get(1).path("content").asText();
        org.assertj.core.api.Assertions.assertThat(compactedRequest)
                .hasSizeLessThanOrEqualTo(6000)
                .contains("contexto adicional compactado")
                .endsWith(currentRequest);
    }

    @Test
    void neverCutsTheCurrentRequestEvenWhenItExceedsThePerMessageBudget() throws Exception {
        String currentRequest = "REQUISITO-IMPORTANTE ".repeat(360) + "FIM-DO-PEDIDO";
        String content = "[Project Analysis]\n" + "contexto antigo ".repeat(600)
                + "\n\nCom base no contexto local acima, responda ao seguinte pedido do usuário:\n\n"
                + currentRequest;

        Method method = AgentService.class.getDeclaredMethod("withBackendIdentityPrompt", ArrayNode.class, List.class);
        method.setAccessible(true);
        ArrayNode guardedMessages = (ArrayNode) method.invoke(service, userMessages(content), List.of());

        org.assertj.core.api.Assertions.assertThat(
                        guardedMessages.get(1).path("content").asText())
                .isEqualTo(currentRequest);
    }

    @Test
    void backendLoadsMaximumPolicyByDefault() throws Exception {
        Method method = AgentService.class.getDeclaredMethod("withBackendIdentityPrompt", ArrayNode.class, List.class);
        method.setAccessible(true);

        ArrayNode guardedMessages = (ArrayNode) method.invoke(
                service, userMessages("Crie uma imagem anatômica para segurança do trabalho."), List.of());

        org.assertj.core.api.Assertions.assertThat(
                        guardedMessages.get(0).path("content").asText())
                .contains("CONTENT POLICY — MAXIMUM MODE")
                .contains("TRANSFORMING USER-PROVIDED CONTENT")
                .contains("Do not censor, soften, omit")
                .contains("Call `generate_image` as soon as the intent is clear")
                .contains("Do not produce pornography, explicit sexual activity, or sexualized nudity");
    }

    @Test
    void sendsDeterministicInferenceParametersToOllama() throws Exception {
        ObjectNode request = buildOllamaRequest("qwen3:8b", userMessages("Analisa este projeto."));

        assertEquals(16384, request.path("options").path("num_ctx").asInt());
        assertEquals(4096, request.path("options").path("num_predict").asInt());
        assertEquals(0.15, request.path("options").path("temperature").asDouble());
        assertEquals(0.9, request.path("options").path("top_p").asDouble());
        assertEquals(30, request.path("options").path("top_k").asInt());
        assertEquals(1.08, request.path("options").path("repeat_penalty").asDouble());
    }

    @Test
    void preservesPreviousExplicitRequestForShortContinuation() throws Exception {
        ArrayNode messages =
                userMessages("Analise a autenticação do projeto e corrija apenas o logout.", "Pode continuar.");
        Method method = AgentService.class.getDeclaredMethod("withBackendIdentityPrompt", ArrayNode.class, List.class);
        method.setAccessible(true);

        ArrayNode guardedMessages = (ArrayNode) method.invoke(service, messages, List.of());

        org.assertj.core.api.Assertions.assertThat(
                        guardedMessages.get(0).path("content").asText())
                .contains("[Conversation Continuity]")
                .contains("corrija apenas o logout");
    }

    @Test
    void generatedImageCompletionEmbedsTheImageInTheChatResponse() throws Exception {
        Method method = AgentService.class.getDeclaredMethod("generatedImageMessage", JsonNode.class);
        method.setAccessible(true);

        ObjectNode result = mapper.createObjectNode().put("path", "/tmp/Avento Generated Images/avento-image-test.png");

        String message = (String) method.invoke(service, result);

        org.assertj.core.api.Assertions.assertThat(message)
                .contains("![Imagem gerada](/api/media/avento-image-test.png)");
    }

    @Test
    void queuedImageCompletionEmbedsTheProgressCardInTheChatResponse() throws Exception {
        Method method = AgentService.class.getDeclaredMethod("generatedImageMessage", JsonNode.class);
        method.setAccessible(true);
        String jobId = "11111111-1111-1111-1111-111111111111";

        String message =
                (String) method.invoke(service, mapper.createObjectNode().put("jobId", jobId));

        org.assertj.core.api.Assertions.assertThat(message).contains("[[avento-image-job:" + jobId + "]]");
    }

    @Test
    void successfulImageGenerationEndsWithoutAnotherModelRound() throws Exception {
        Object toolCall = buildGenerateImageToolCall("um estacionamento com um carro vermelho", "comfyui:model");
        ObjectNode success =
                mapper.createObjectNode().put("status", "success").put("path", "/tmp/avento-image-test.png");
        ObjectNode failure = mapper.createObjectNode().put("error", "generation failed");
        Method method = AgentService.class.getDeclaredMethod(
                "isSuccessfulMediaGeneration", toolCall.getClass(), JsonNode.class);
        method.setAccessible(true);

        assertTrue((boolean) method.invoke(service, toolCall, success));
        assertFalse((boolean) method.invoke(service, toolCall, failure));
    }

    @Test
    void doesNotStreamEditorialTextWhileMediaToolIsBeingChosen() throws Exception {
        Class<?> captureClass = Class.forName("com.avento.service.AgentService$TurnCapture");
        Constructor<?> captureConstructor = captureClass.getDeclaredConstructor(boolean.class);
        captureConstructor.setAccessible(true);
        Object capture = captureConstructor.newInstance(true);

        Class<?> stateClass = Class.forName("com.avento.service.AgentService$AgentRunState");
        Method method = AgentService.class.getDeclaredMethod(
                "handleModelChunk", String.class, FluxSink.class, captureClass, stateClass, String.class);
        method.setAccessible(true);

        String modelLine = mapper.createObjectNode()
                .set(
                        "message",
                        mapper.createObjectNode()
                                .put("role", "assistant")
                                .put("content", "*Nota: a geração está sujeita a restrições técnicas de memória.*"))
                .toString();

        List<String> emitted = Flux.<String>create(sink -> {
                    try {
                        method.invoke(service, modelLine, sink, capture, null, "qwen3");
                        sink.complete();
                    } catch (Exception exception) {
                        sink.error(exception);
                    }
                })
                .collectList()
                .block(Duration.ofSeconds(2));

        assertNotNull(emitted);
        org.assertj.core.api.Assertions.assertThat(String.join("", emitted)).doesNotContain("Nota:");

        Field assistantTextField = captureClass.getDeclaredField("assistantText");
        assistantTextField.setAccessible(true);
        StringBuilder assistantText = (StringBuilder) assistantTextField.get(capture);
        org.assertj.core.api.Assertions.assertThat(assistantText.toString()).contains("restrições técnicas");
    }

    @Test
    void backendDoesNotExposeToolsForCasualModelRequest() throws Exception {
        ObjectNode request = buildOllamaRequest("qwen3:8b", userMessages("oi"));

        assertTrue(!request.has("tools") || request.path("tools").isEmpty());
    }

    @Test
    void backendExposesOnlyRelevantToolsForProjectAnalysisRequest() throws Exception {
        ObjectNode request = buildOllamaRequest("qwen3:8b", userMessages("Analisa esse projeto para mim."));
        ArrayNode tools = (ArrayNode) request.path("tools");

        assertTrue(tools.size() > 0);
        org.assertj.core.api.Assertions.assertThat(tools.toString())
                .contains("directory_tree")
                .contains("read_file")
                .doesNotContain("run_shortcut")
                .doesNotContain("terminal_start");
    }

    @Test
    void backendDoesNotExposeToolsForVisionImageAnalysisRequest() throws Exception {
        ObjectNode request = buildOllamaRequest("qwen2.5vl:7b", userMessagesWithImage("analisa pra mim"));

        assertTrue(!request.has("tools") || request.path("tools").isEmpty());
        JsonNode lastMessage =
                request.path("messages").get(request.path("messages").size() - 1);
        assertEquals("user", lastMessage.path("role").asText());
        assertEquals("fake-base64-image", lastMessage.path("images").get(0).asText());
    }

    @Test
    void backendKeepsToolsDisabledWhenFollowingUpOnAnEarlierImage() throws Exception {
        ArrayNode messages = userMessagesWithImage("analisa pra mim");
        messages.addObject().put("role", "assistant").put("content", "Vou analisar.");
        messages.addObject().put("role", "user").put("content", "analisa essa imagem pra mim");

        ObjectNode request = buildOllamaRequest("qwen2.5vl:7b", messages);

        assertTrue(!request.has("tools") || request.path("tools").isEmpty());
    }

    @Test
    void backendRoutesImageContextFromTextModelToConfiguredVisionModel() throws Exception {
        // Ganhou o userId: o modelo de visao passou a vir da TELA de provedores, com o valor de
        // configuracao so como padrao. Sem usuario configurado, o padrao continua valendo.
        Method method =
                AgentService.class.getDeclaredMethod("resolveChatModel", String.class, ArrayNode.class, UUID.class);
        method.setAccessible(true);

        String resolved = (String) method.invoke(service, "qwen3:8b", userMessagesWithImage("analisa pra mim"), null);

        assertEquals("qwen2.5vl:7b", resolved);
    }

    @Test
    void passesToolCountQuestionToTheModel() throws Exception {
        String directResponse = detectDirectConversationResponse("Quantas ferramentas você tem?");

        assertNull(directResponse);
    }

    @Test
    void detectsImageGenerationRequestsBeforeCallingTheModel() throws Exception {
        Object toolCall = detectImageGenerationToolCall("Gera uma imagem de um pitbull em estilo cartoon.");

        assertToolCallArgument(
                toolCall, "generate_image", "prompt", "Gera uma imagem de um pitbull em estilo cartoon.");
    }

    @Test
    void detectsImageGenerationRequestUsingImperativeGere() throws Exception {
        Object toolCall = detectImageGenerationToolCall("Gere uma imagem realista de um carro vermelho.");

        assertToolCallArgument(toolCall, "generate_image", "prompt", "Gere uma imagem realista de um carro vermelho.");
    }

    @Test
    void detectsNaturalImageGenerationRequestWithPraMim() throws Exception {
        Object toolCall = detectImageGenerationToolCall("gera pra mim a imagem de um pitbull marrom filhote");

        assertToolCallArgument(
                toolCall, "generate_image", "prompt", "gera pra mim a imagem de um pitbull marrom filhote");
    }

    @Test
    void detectsArtisticImageRequestWithoutExplicitGenerateVerb() throws Exception {
        Object toolCall =
                detectImageGenerationToolCall("Quero uma imagem artística de uma mulher em estilo editorial.");

        assertToolCallArgument(
                toolCall, "generate_image", "prompt", "Quero uma imagem artística de uma mulher em estilo editorial.");
    }

    @Test
    void detectsStandaloneVisualPromptWithoutGenerationVerb() throws Exception {
        String prompt = "Macro product photography of a mechanical watch, centered composition, black background, "
                + "sharp focus, dramatic studio lighting, photorealistic, 4K";

        Object toolCall = detectImageGenerationToolCall(prompt);

        assertToolCallArgument(toolCall, "generate_image", "prompt", prompt);
    }

    @Test
    void doesNotGenerateWhenUserIsDiscussingAVisualPrompt() throws Exception {
        ArrayNode messages = userMessages(
                "Analise este prompt de fotografia photorealistic com macro, black background, sharp focus, "
                        + "studio lighting e composição centralizada; explique por que o resultado ficou ruim.");
        Method detector = AgentService.class.getDeclaredMethod("detectDirectImageGenerationRequest", ArrayNode.class);
        detector.setAccessible(true);

        assertNull(detector.invoke(service, messages));
    }

    @Test
    void reusesPreviousImagePromptForGenericGenerationFollowUp() throws Exception {
        ArrayNode messages = userMessages(
                "Quero um pitbull em estilo cartoon, com fundo urbano e traço moderno.",
                "Cara gera a imagem que eue pedi amigo");

        Object toolCall = detectImageGenerationToolCall(messages);

        assertToolCallArgument(
                toolCall,
                "generate_image",
                "prompt",
                "Quero um pitbull em estilo cartoon, com fundo urbano e traço moderno.");
    }

    @Test
    void retriesPreviousImagePromptForShortFollowUp() throws Exception {
        ArrayNode messages = userMessages(
                "Gera uma imagem de um pitbull em estilo cartoon.",
                "Erro ao chamar o modelo local x/flux2-klein:4b.",
                "tenta agora");

        Object toolCall = detectImageGenerationToolCall(messages);

        assertToolCallArgument(
                toolCall, "generate_image", "prompt", "Gera uma imagem de um pitbull em estilo cartoon.");
    }

    @Test
    void testsWithAvailableModelAsImageRetry() throws Exception {
        ArrayNode messages = userMessages(
                "gera pra mim a imagem de um pitbull marrom filhote",
                "Parece que o modelo flux2 não está disponível no Ollama.",
                "testa com o que tiver");

        Object toolCall = detectImageGenerationToolCall(messages);

        assertToolCallArgument(
                toolCall, "generate_image", "prompt", "gera pra mim a imagem de um pitbull marrom filhote");
    }

    @Test
    void uiSelectedImageModelAlwaysOverridesWhatTheModelPutInItsOwnToolCall() throws Exception {
        Object toolCall = buildGenerateImageToolCall("um gato", "qwen-image-model-the-llm-picked");

        Object result = withImageModel(toolCall, "comfyui:realistic-vision");

        assertToolCallArgument(result, "generate_image", "model", "comfyui:realistic-vision");
    }

    @Test
    void modelsOwnChoiceIsUsedOnlyWhenNoUiModelIsSelected() throws Exception {
        Object toolCall = buildGenerateImageToolCall("um gato", "qwen-image-model-the-llm-picked");

        Object result = withImageModel(toolCall, "");

        assertToolCallArgument(result, "generate_image", "model", "qwen-image-model-the-llm-picked");
    }

    @Test
    void uiImageOptionsOverrideArgumentsInventedByTheModel() throws Exception {
        Object toolCall = buildGenerateImageToolCall("um gato", "comfyui:model.safetensors");
        ImageGenerationOptions options = new ImageGenerationOptions("quality", "portrait", "animal", 123L, 1, true);
        Method method =
                AgentService.class.getDeclaredMethod("withImageOptions", toolCall.getClass(), options.getClass());
        method.setAccessible(true);

        Object result = method.invoke(service, toolCall, options);

        assertToolCallArgument(result, "generate_image", "qualityPreset", "quality");
        assertToolCallArgument(result, "generate_image", "aspectRatio", "portrait");
        assertToolCallArgument(result, "generate_image", "subjectType", "animal");
        assertToolCallArgument(result, "generate_image", "size", "512x768");
        assertToolCallArgument(result, "generate_image", "seed", "123");
        assertToolCallArgument(result, "generate_image", "subjectCount", "1");
        assertToolCallArgument(result, "generate_image", "referenceStrength", "0.65");
    }

    private Object buildGenerateImageToolCall(String prompt, String model) throws Exception {
        Class<?> toolCallClass = Class.forName("com.avento.service.dto.ToolCall");
        Constructor<?> constructor = toolCallClass.getDeclaredConstructor(String.class, String.class, JsonNode.class);
        constructor.setAccessible(true);
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("prompt", prompt);
        arguments.put("model", model);
        return constructor.newInstance("call_1", "generate_image", arguments);
    }

    private Object withImageModel(Object toolCall, String imageModel) throws Exception {
        Method method = AgentService.class.getDeclaredMethod("withImageModel", toolCall.getClass(), String.class);
        method.setAccessible(true);
        return method.invoke(service, toolCall, imageModel);
    }

    @Test
    void retriesImagePromptFromAssistantErrorContext() throws Exception {
        ArrayNode messages = mapper.createArrayNode();
        ObjectNode assistantMessage = messages.addObject();
        assistantMessage.put("role", "assistant");
        assistantMessage.put(
                "content",
                "Erro ao chamar o modelo local x/flux2-klein:4b: does not support chat. Era a imagem do Pitbull.");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", "tenta de novo");

        Object toolCall = detectImageGenerationToolCall(messages);

        assertToolCallArgument(toolCall, "generate_image", "prompt", "Gere uma imagem de Pitbull.");
    }

    @Test
    void reusesPreviousImagePromptWhenSubjectIsMentionedWithoutImageWord() throws Exception {
        ArrayNode messages =
                userMessages("Gera uma imagem de um pitbull em estilo cartoon.", "gere o pitbull que eu pedi");

        Object toolCall = detectImageGenerationToolCall(messages);

        assertToolCallArgument(
                toolCall, "generate_image", "prompt", "Gera uma imagem de um pitbull em estilo cartoon.");
    }

    // Era um teste por reflexao num metodo privado do AgentService. A regra virou funcao pura em
    // ModelNames, entao a chamada e direta — o caso continua aqui porque o defeito que o gerou foi
    // um modelo de imagem escolhido para conversar, e e nesse arquivo que esse defeito e procurado.
    @Test
    void imageGenerationModelsAreNotUsedAsChatModels() {
        assertEquals("qwen3:8b", ModelNames.normalizeChatModel("x/flux2-klein:4b", "qwen3:8b"));
        assertEquals("qwen3:8b", ModelNames.normalizeChatModel("x/z-image-turbo:latest", "qwen3:8b"));
        assertEquals("qwen3:8b", ModelNames.normalizeChatModel("qwen3:8b", "qwen3:8b"));
    }

    @Test
    void streamChatRequestsApprovalForDirectImageGeneration() {
        ArrayNode messages = userMessages("Gera uma imagem de um pitbull em estilo cartoon.");

        String firstChunk = service.streamChat("llama3", messages).blockFirst(Duration.ofSeconds(2));

        assertNotNull(firstChunk);
        org.assertj.core.api.Assertions.assertThat(firstChunk)
                .contains("tool.approval.required")
                .contains("generate_image");
    }

    @Test
    void directImageGenerationFailureMessageIncludesDetailsAndHint() throws Exception {
        Object toolCall = detectImageGenerationToolCall("Gera uma imagem de um pitbull em estilo cartoon.");
        ObjectNode toolResult = mapper.createObjectNode();
        toolResult.put("error", "O modelo de imagem nao coube na memoria disponivel do Ollama.");
        toolResult.put("details", "model requires more memory than is available");
        toolResult.put("hint", "Feche apps pesados ou configure AVENTO_IMAGE_DEFAULT_MODEL.");

        String message = directToolCompletionMessage(toolCall, toolResult);

        org.assertj.core.api.Assertions.assertThat(message)
                .contains("Não consegui executar `generate_image`")
                .contains("model requires more memory")
                .contains("AVENTO_IMAGE_DEFAULT_MODEL");
    }

    @Test
    void detectsScreenCaptureRequestBeforeCallingTheModel() throws Exception {
        Object toolCall = detectToolCall("Tira um print da minha tela.");

        assertToolCallName(toolCall, "capture_screen");
    }

    @Test
    void streamChatRequestsApprovalForScreenCaptureWithoutCallingModel() {
        ArrayNode messages = userMessages("Tira um print da minha tela.");

        String firstChunk = service.streamChat("qwen3:8b", messages).blockFirst(Duration.ofSeconds(2));

        assertNotNull(firstChunk);
        org.assertj.core.api.Assertions.assertThat(firstChunk)
                .contains("tool.approval.required")
                .contains("capture_screen");
    }

    @Test
    void secondResolutionOfTheSameApprovalReportsAlreadyProcessedInsteadOfNotFound() throws Exception {
        ArrayNode messages = userMessages("Tira um print da minha tela.");
        String firstChunk = service.streamChat("qwen3:8b", messages).blockFirst(Duration.ofSeconds(2));
        JsonNode approvalEvent =
                mapper.readTree(firstChunk.substring(firstChunk.indexOf('{'))).path("avento_event");
        String approvalId = approvalEvent.path("approvalId").asText();
        assertFalse(approvalId.isBlank());

        // Simulates the UI button and a voice/text "aprovo" phrase racing for the same
        // approvalId: whichever resolves it first wins, the second must not see a scary
        // "no pending execution" message for something that was already handled.
        String firstResolution = String.join(
                "", service.rejectTool(approvalId, null).collectList().block(Duration.ofSeconds(2)));
        String secondResolution = String.join(
                "", service.rejectTool(approvalId, null).collectList().block(Duration.ofSeconds(2)));

        org.assertj.core.api.Assertions.assertThat(firstResolution).contains("Ação cancelada");
        org.assertj.core.api.Assertions.assertThat(secondResolution)
                .contains("tool.approval.already_completed")
                .contains("já foi aprovada")
                .doesNotContain("Não encontrei uma execução pendente");
    }

    @Test
    void resolvingOneApprovalInvalidatesDuplicateApprovalsFromTheSameRun() throws Exception {
        registerPendingApproval("approval_primary", "capture_screen", "run_duplicate");
        registerPendingApproval("approval_duplicate", "capture_screen", "run_duplicate");

        String firstResolution = String.join(
                "", service.rejectTool("approval_primary", null).collectList().block(Duration.ofSeconds(2)));
        String duplicateResolution = String.join(
                "", service.rejectTool("approval_duplicate", null).collectList().block(Duration.ofSeconds(2)));

        org.assertj.core.api.Assertions.assertThat(firstResolution).contains("Ação cancelada");
        org.assertj.core.api.Assertions.assertThat(duplicateResolution)
                .contains("tool.approval.already_completed")
                .contains("Nada foi executado de novo");
    }

    @Test
    void voiceCanRejectLatestPendingApprovalWithoutClicking() throws Exception {
        ArrayNode messages = userMessages("Tira um print da minha tela.");
        String firstChunk = service.streamChat("qwen3:8b", messages).blockFirst(Duration.ofSeconds(2));
        JsonNode approvalEvent =
                mapper.readTree(firstChunk.substring(firstChunk.indexOf('{'))).path("avento_event");

        String response = String.join(
                "",
                service.streamChat("qwen3:8b", userMessages("Não executa, cancela isso."))
                        .collectList()
                        .block(Duration.ofSeconds(2)));

        assertFalse(approvalEvent.path("approvalId").asText().isBlank());
        org.assertj.core.api.Assertions.assertThat(response)
                .contains("tool.rejected")
                .contains("Ação cancelada")
                .contains("capture_screen");
    }

    @Test
    void voiceCanApproveLatestPendingApprovalWithDuration() throws Exception {
        ArrayNode messages = userMessages("Tira um print da minha tela.");
        String firstChunk = service.streamChat("qwen3:8b", messages).blockFirst(Duration.ofSeconds(2));
        JsonNode approvalEvent =
                mapper.readTree(firstChunk.substring(firstChunk.indexOf('{'))).path("avento_event");

        String response = String.join(
                "",
                service.streamChat("qwen3:8b", userMessages("Aprovo por 24 horas nesse projeto."))
                        .collectList()
                        .block(Duration.ofSeconds(2)));

        assertFalse(approvalEvent.path("approvalId").asText().isBlank());
        org.assertj.core.api.Assertions.assertThat(response)
                .contains("tool.approval.accepted")
                .contains("tool.permission.remembered");
    }

    @Test
    void approvingANonDestructiveToolGrantsPlanApprovalForTheRestOfTheRun() throws Exception {
        String runId = "run-plan-open-app";
        String approvalId = "approval-plan-open-app";
        registerPendingApproval(approvalId, "open_app", runId);

        String response = String.join(
                "", service.approveTool(approvalId, null).collectList().block(Duration.ofSeconds(2)));

        org.assertj.core.api.Assertions.assertThat(response).contains("tool.approval.accepted");
        assertTrue(planApprovedRuns().contains(runId));
    }

    @Test
    void approvingAnAlwaysConfirmToolDoesNotGrantPlanApproval() throws Exception {
        String runId = "run-plan-delete-file";
        String approvalId = "approval-plan-delete-file";
        registerPendingApproval(approvalId, "delete_file", runId);

        String response = String.join(
                "", service.approveTool(approvalId, null).collectList().block(Duration.ofSeconds(2)));

        org.assertj.core.api.Assertions.assertThat(response).contains("tool.approval.accepted");
        assertFalse(planApprovedRuns().contains(runId));
    }

    @Test
    void approvingATerminalRunWithRmRfDoesNotGrantPlanApproval() throws Exception {
        String runId = "run-plan-rm-rf";
        String approvalId = "approval-plan-rm-rf";
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("path", "/tmp");
        arguments.put("command", "rm -rf back");
        registerPendingApproval(approvalId, "terminal_run", runId, arguments);

        String response = String.join(
                "", service.approveTool(approvalId, null).collectList().block(Duration.ofSeconds(2)));

        org.assertj.core.api.Assertions.assertThat(response).contains("tool.approval.accepted");
        assertFalse(planApprovedRuns().contains(runId));
    }

    @Test
    void approvingATerminalRunWithHarmlessCommandGrantsPlanApproval() throws Exception {
        String runId = "run-plan-npm-test";
        String approvalId = "approval-plan-npm-test";
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("path", "/tmp");
        arguments.put("command", "npm test");
        registerPendingApproval(approvalId, "terminal_run", runId, arguments);

        String response = String.join(
                "", service.approveTool(approvalId, null).collectList().block(Duration.ofSeconds(2)));

        org.assertj.core.api.Assertions.assertThat(response).contains("tool.approval.accepted");
        assertTrue(planApprovedRuns().contains(runId));
    }

    private void registerPendingApproval(String approvalId, String toolName, String runId) throws Exception {
        registerPendingApproval(approvalId, toolName, runId, mapper.createObjectNode());
    }

    @SuppressWarnings("unchecked")
    private void registerPendingApproval(String approvalId, String toolName, String runId, JsonNode arguments)
            throws Exception {
        Class<?> toolCallClass = Class.forName("com.avento.service.dto.ToolCall");
        Constructor<?> toolCallConstructor =
                toolCallClass.getDeclaredConstructor(String.class, String.class, JsonNode.class);
        toolCallConstructor.setAccessible(true);
        Object toolCall = toolCallConstructor.newInstance("call_" + approvalId, toolName, arguments);

        Class<?> pendingClass = Class.forName("com.avento.service.dto.PendingToolExecution");
        Constructor<?> pendingConstructor = pendingClass.getDeclaredConstructor(
                String.class,
                ArrayNode.class,
                int.class,
                int.class,
                toolCallClass,
                boolean.class,
                List.class,
                String.class);
        pendingConstructor.setAccessible(true);
        Object pending = pendingConstructor.newInstance(
                "qwen3:8b", mapper.createArrayNode(), 0, 1, toolCall, false, List.<String>of(), runId);

        Field pendingField = AgentService.class.getDeclaredField("pendingToolExecutions");
        pendingField.setAccessible(true);
        ((Map<String, Object>) pendingField.get(service)).put(approvalId, pending);
    }

    @SuppressWarnings("unchecked")
    private Set<String> planApprovedRuns() throws Exception {
        Field field = AgentService.class.getDeclaredField("planApprovedRuns");
        field.setAccessible(true);
        return (Set<String>) field.get(service);
    }

    @Test
    void retriesWithFullToolsetWhenFirstRoundHasNoToolCallsForNonCasualMessage() throws Exception {
        assertTrue(shouldRetryWithFullToolset(1, false, false, "Desenha uma cidade futurista pra mim"));
    }

    @Test
    void doesNotRetryForCasualMessage() throws Exception {
        assertFalse(shouldRetryWithFullToolset(1, false, false, "Bom dia"));
    }

    @Test
    void doesNotRetryInformationalQuestionWithASecondModelAnswer() throws Exception {
        assertFalse(shouldRetryWithFullToolset(1, false, false, "Me fala quantas ferramentas voce tem"));
    }

    @Test
    void doesNotRetryAfterTheFirstRound() throws Exception {
        assertFalse(shouldRetryWithFullToolset(2, false, false, "Desenha uma cidade futurista pra mim"));
    }

    @Test
    void doesNotRetryTwiceInTheSameResponse() throws Exception {
        assertFalse(shouldRetryWithFullToolset(1, true, false, "Desenha uma cidade futurista pra mim"));
    }

    @Test
    void doesNotRetryWhenAlreadyUsingTheFullToolset() throws Exception {
        assertFalse(shouldRetryWithFullToolset(1, false, true, "Desenha uma cidade futurista pra mim"));
    }

    @Test
    void doesNotRetryProjectAnalysisWithEveryToolAfterTheModelAlreadyAnswered() throws Exception {
        assertFalse(shouldRetryWithFullToolset(1, false, false, """
                [Project Analysis]
                Nome: matsutech-sti
                Stack: Node.js, TypeScript

                Com base no contexto acima, analisa esse projeto pra mim.
                """));
    }

    @Test
    void warnsWhenActionableRequestExecutedZeroTools() throws Exception {
        assertTrue(shouldWarnAboutNoToolExecution(0, "Cria esse projeto com nestjs pra mim na pasta back"));
    }

    @Test
    void doesNotWarnWhenAtLeastOneToolWasExecuted() throws Exception {
        assertFalse(shouldWarnAboutNoToolExecution(1, "Cria esse projeto com nestjs pra mim na pasta back"));
    }

    @Test
    void doesNotWarnForCasualMessageWithZeroTools() throws Exception {
        assertFalse(shouldWarnAboutNoToolExecution(0, "Bom dia"));
    }

    @Test
    void doesNotWarnForInformationalQuestionWithZeroTools() throws Exception {
        assertFalse(shouldWarnAboutNoToolExecution(0, "Me explica para que serve o Redis"));
    }

    private boolean shouldWarnAboutNoToolExecution(int executedToolCalls, String userMessage) throws Exception {
        Class<?> stateClass = Class.forName("com.avento.service.AgentService$AgentRunState");
        Constructor<?> stateConstructor = stateClass.getDeclaredConstructor();
        stateConstructor.setAccessible(true);
        Object state = stateConstructor.newInstance();

        Field executedToolCallsField = stateClass.getDeclaredField("executedToolCalls");
        executedToolCallsField.setAccessible(true);
        executedToolCallsField.set(state, executedToolCalls);

        Method method =
                AgentService.class.getDeclaredMethod("shouldWarnAboutNoToolExecution", stateClass, ArrayNode.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service, state, userMessages(userMessage));
    }

    private boolean shouldRetryWithFullToolset(int round, boolean retried, boolean forced, String userMessage)
            throws Exception {
        Class<?> stateClass = Class.forName("com.avento.service.AgentService$AgentRunState");
        Constructor<?> stateConstructor = stateClass.getDeclaredConstructor();
        stateConstructor.setAccessible(true);
        Object state = stateConstructor.newInstance();

        Field retriedField = stateClass.getDeclaredField("retriedWithFullToolset");
        retriedField.setAccessible(true);
        retriedField.set(state, retried);

        Field forcedField = stateClass.getDeclaredField("forceFullToolset");
        forcedField.setAccessible(true);
        forcedField.set(state, forced);

        Method method = AgentService.class.getDeclaredMethod(
                "shouldRetryWithFullToolset", stateClass, int.class, ArrayNode.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service, state, round, userMessages(userMessage));
    }

    @Test
    void backendExposesScreenCaptureToolForPrintRequest() throws Exception {
        ObjectNode request = buildOllamaRequest("qwen3:8b", userMessages("Tira um print da minha tela."));
        ArrayNode tools = (ArrayNode) request.path("tools");

        org.assertj.core.api.Assertions.assertThat(tools.toString())
                .contains("capture_screen")
                .doesNotContain("run_shortcut")
                .doesNotContain("open_app");
    }

    @Test
    void emitsRealTokenCountFromOllamaDoneLine() throws Exception {
        Class<?> captureClass = Class.forName("com.avento.service.AgentService$TurnCapture");
        Constructor<?> captureConstructor = captureClass.getDeclaredConstructor();
        captureConstructor.setAccessible(true);
        Object capture = captureConstructor.newInstance();

        Class<?> stateClass = Class.forName("com.avento.service.AgentService$AgentRunState");
        Method method = AgentService.class.getDeclaredMethod(
                "handleModelChunk", String.class, FluxSink.class, captureClass, stateClass, String.class);
        method.setAccessible(true);

        String ollamaDoneLine =
                "{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"eval_count\":17}";

        List<String> emitted = Flux.<String>create(sink -> {
                    try {
                        Constructor<?> stateConstructor = stateClass.getDeclaredConstructor();
                        stateConstructor.setAccessible(true);
                        Object state = stateConstructor.newInstance();
                        method.invoke(service, ollamaDoneLine, sink, capture, state, "qwen3");
                        sink.complete();
                    } catch (Exception exception) {
                        sink.error(exception);
                    }
                })
                .collectList()
                .block(Duration.ofSeconds(2));

        assertNotNull(emitted);
        org.assertj.core.api.Assertions.assertThat(String.join("", emitted))
                .contains("agent.tokens.usage")
                .contains("\"evalCount\":17");
    }

    @Test
    void doesNotEmitTokenUsageEventWhenOllamaLineIsNotDone() throws Exception {
        Class<?> captureClass = Class.forName("com.avento.service.AgentService$TurnCapture");
        Constructor<?> captureConstructor = captureClass.getDeclaredConstructor();
        captureConstructor.setAccessible(true);
        Object capture = captureConstructor.newInstance();

        Class<?> stateClass = Class.forName("com.avento.service.AgentService$AgentRunState");
        Method method = AgentService.class.getDeclaredMethod(
                "handleModelChunk", String.class, FluxSink.class, captureClass, stateClass, String.class);
        method.setAccessible(true);

        String ollamaPartialLine = "{\"message\":{\"role\":\"assistant\",\"content\":\"Oi\"},\"done\":false}";

        List<String> emitted = Flux.<String>create(sink -> {
                    try {
                        method.invoke(service, ollamaPartialLine, sink, capture, null, "qwen3");
                        sink.complete();
                    } catch (Exception exception) {
                        sink.error(exception);
                    }
                })
                .collectList()
                .block(Duration.ofSeconds(2));

        assertNotNull(emitted);
        org.assertj.core.api.Assertions.assertThat(String.join("", emitted)).doesNotContain("agent.tokens.usage");
    }

    @Test
    void reportsAClearErrorInsteadOfHangingWhenOllamaStreamStalls() throws Exception {
        Class<?> stateClass = Class.forName("com.avento.service.AgentService$AgentRunState");
        Constructor<?> stateConstructor = stateClass.getDeclaredConstructor();
        stateConstructor.setAccessible(true);
        Object state = stateConstructor.newInstance();

        Method method = AgentService.class.getDeclaredMethod(
                "handleStreamError",
                String.class,
                ArrayNode.class,
                stateClass,
                int.class,
                FluxSink.class,
                Throwable.class);
        method.setAccessible(true);

        List<String> emitted = Flux.<String>create(sink -> {
                    try {
                        method.invoke(
                                service,
                                "qwen3:8b",
                                userMessages("oi"),
                                state,
                                1,
                                sink,
                                new TimeoutException("Did not observe any item or terminal signal within 120000ms"));
                        sink.complete();
                    } catch (Exception exception) {
                        sink.error(exception);
                    }
                })
                .collectList()
                .block(Duration.ofSeconds(2));

        assertNotNull(emitted);
        org.assertj.core.api.Assertions.assertThat(String.join("", emitted))
                .contains("agent.error")
                .contains("ficou mais de 2 minutos sem gerar nada")
                .doesNotContain("120000ms");
    }

    @Test
    void forwardsThinkingFieldWrappedInThinkTagsSoTheFrontendParserCanShowIt() throws Exception {
        Class<?> captureClass = Class.forName("com.avento.service.AgentService$TurnCapture");
        Constructor<?> captureConstructor = captureClass.getDeclaredConstructor();
        captureConstructor.setAccessible(true);
        Object capture = captureConstructor.newInstance();

        Class<?> stateClass = Class.forName("com.avento.service.AgentService$AgentRunState");
        Method method = AgentService.class.getDeclaredMethod(
                "handleModelChunk", String.class, FluxSink.class, captureClass, stateClass, String.class);
        method.setAccessible(true);

        String ollamaThinkingLine =
                "{\"message\":{\"role\":\"assistant\",\"thinking\":\"analisando o pedido\",\"content\":\"\"},\"done\":false}";

        List<String> emitted = Flux.<String>create(sink -> {
                    try {
                        method.invoke(service, ollamaThinkingLine, sink, capture, null, "qwen3");
                        sink.complete();
                    } catch (Exception exception) {
                        sink.error(exception);
                    }
                })
                .collectList()
                .block(Duration.ofSeconds(2));

        assertNotNull(emitted);
        org.assertj.core.api.Assertions.assertThat(String.join("", emitted))
                .contains("<think>analisando o pedido</think>");

        Field assistantTextField = captureClass.getDeclaredField("assistantText");
        assistantTextField.setAccessible(true);
        StringBuilder assistantText = (StringBuilder) assistantTextField.get(capture);
        org.assertj.core.api.Assertions.assertThat(assistantText.toString()).doesNotContain("analisando o pedido");
    }

    @Test
    void plainMessageWithoutSlashIsNotTreatedAsASkillInvocation() throws Exception {
        Object resolution = resolveSkillInvocation("Cria um projeto NestJS pra mim");

        assertFalse((boolean) invocationField(resolution, "invoked"));
    }

    @Test
    void unknownSkillNameRepliesWithoutCallingTheModel() throws Exception {
        Object resolution = resolveSkillInvocation("/does-not-exist algum argumento");

        assertTrue((boolean) invocationField(resolution, "invoked"));
        assertFalse((boolean) invocationField(resolution, "found"));
        org.assertj.core.api.Assertions.assertThat((String) invocationField(resolution, "notFoundReply"))
                .contains("does-not-exist")
                .contains("nestjs-project");
    }

    @Test
    void knownSkillExpandsTheUserMessageWithTheSkillBodyAndArgument() throws Exception {
        Object resolution = resolveSkillInvocation("/nestjs-project back");

        assertTrue((boolean) invocationField(resolution, "invoked"));
        assertTrue((boolean) invocationField(resolution, "found"));
        ArrayNode augmented = (ArrayNode) invocationField(resolution, "augmentedMessages");
        String expandedContent =
                augmented.get(augmented.size() - 1).path("content").asText();
        org.assertj.core.api.Assertions.assertThat(expandedContent)
                .contains("[Skill: nestjs-project]")
                .contains("npx --yes @nestjs/cli@latest new")
                .contains("Argumento fornecido pelo usuário: back");
    }

    @Test
    void videoSkillInvocationCapturesTheVideoToolAndArgumentNotImage() throws Exception {
        Object resolution = resolveSkillInvocation("/generate-video um carro vermelho acelerando na chuva");

        assertTrue((boolean) invocationField(resolution, "invoked"));
        assertTrue((boolean) invocationField(resolution, "found"));
        assertEquals("generate_video", (String) invocationField(resolution, "toolName"));
        assertEquals("um carro vermelho acelerando na chuva", (String) invocationField(resolution, "argument"));
    }

    @Test
    void imageSkillInvocationCapturesTheImageToolAndArgument() throws Exception {
        Object resolution = resolveSkillInvocation("/generate-image uma paisagem futurista ao entardecer");

        assertEquals("generate_image", (String) invocationField(resolution, "toolName"));
        assertEquals("uma paisagem futurista ao entardecer", (String) invocationField(resolution, "argument"));
    }

    @Test
    void videoSkillWithoutArgumentStillCarriesTheToolForForcedExposure() throws Exception {
        Object resolution = resolveSkillInvocation("/generate-video");

        assertTrue((boolean) invocationField(resolution, "invoked"));
        assertEquals("generate_video", (String) invocationField(resolution, "toolName"));
        assertEquals("", (String) invocationField(resolution, "argument"));
    }

    @Test
    void skillAutoActivatesWhenTheMessageMatchesATriggerWithoutAnySlash() throws Exception {
        Object resolution =
                resolveAutoSkillActivation("Agora vc vai executar criar um projeto nestjs dentro dessa pasta back");

        assertTrue((boolean) invocationField(resolution, "invoked"));
        assertTrue((boolean) invocationField(resolution, "found"));
        ArrayNode augmented = (ArrayNode) invocationField(resolution, "augmentedMessages");
        String expandedContent =
                augmented.get(augmented.size() - 1).path("content").asText();
        org.assertj.core.api.Assertions.assertThat(expandedContent)
                .contains("Agora vc vai executar criar um projeto nestjs dentro dessa pasta back")
                .contains("[Skill ativada automaticamente: nestjs-project]")
                .contains("npx --yes @nestjs/cli@latest new");
    }

    @Test
    void translationSkillPreservesTheOriginalExplicitSourceWhenAutoActivated() throws Exception {
        String request = "Traduza este texto para inglês: O texto adulto explícito deve permanecer integral.";

        Object resolution = resolveAutoSkillActivation(request);

        assertTrue((boolean) invocationField(resolution, "invoked"));
        assertTrue((boolean) invocationField(resolution, "found"));
        ArrayNode augmented = (ArrayNode) invocationField(resolution, "augmentedMessages");
        String expandedContent =
                augmented.get(augmented.size() - 1).path("content").asText();
        org.assertj.core.api.Assertions.assertThat(expandedContent)
                .contains(request)
                .contains("[Skill ativada automaticamente: translate-content]")
                .contains("Do not censor, soften, omit");
    }

    @Test
    void skillDoesNotAutoActivateForCasualOrUnrelatedMessages() throws Exception {
        assertFalse((boolean) invocationField(resolveAutoSkillActivation("Bom dia"), "invoked"));
        assertFalse((boolean) invocationField(
                resolveAutoSkillActivation("cria uma pasta chamada back nesse repositorio"), "invoked"));
    }

    @Test
    void slashSkillsListsEveryAvailableSkillWithoutCallingTheModel() throws Exception {
        Object resolution = resolveSkillInvocation("/skills");

        assertTrue((boolean) invocationField(resolution, "invoked"));
        assertFalse((boolean) invocationField(resolution, "found"));
        org.assertj.core.api.Assertions.assertThat((String) invocationField(resolution, "notFoundReply"))
                .contains("/nestjs-project")
                .contains("/analyze-project")
                .contains("/generate-image")
                .contains("/inspect-database")
                .contains("Cria um projeto NestJS");
    }

    @Test
    void skillActivationEmitsAVisibleEventAsTheFirstChunk() {
        String firstChunk = service.streamChat("qwen3:8b", userMessages("/nestjs-project back"))
                .blockFirst(Duration.ofSeconds(2));

        assertNotNull(firstChunk);
        org.assertj.core.api.Assertions.assertThat(firstChunk).contains("skill.activated");
    }

    @Test
    void skillInvocationSkipsDirectDetectorsAndGoesStraightToTheModel() {
        // O corpo da skill contem "use terminal_run" — o detector de automacao direta ja
        // interpretou esse "terminal" como pedido de abrir o app Terminal. Skill ativada tem que
        // ir direto pro modelo: a resposta pode ate falhar (Ollama nao roda nos testes), mas
        // nunca pode conter uma execucao/aprovacao de open_app.
        List<String> chunks = service.streamChat("qwen3:8b", userMessages("/nestjs-project back"))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(chunks);
        String response = String.join("", chunks);
        org.assertj.core.api.Assertions.assertThat(response)
                .contains("skill.activated")
                .contains("agent.round.started")
                .doesNotContain("open_app")
                .doesNotContain("Aplicativo aberto");
    }

    private Object resolveSkillInvocation(String userMessage) throws Exception {
        Method method = AgentService.class.getDeclaredMethod("resolveSkillInvocation", ArrayNode.class);
        method.setAccessible(true);
        return method.invoke(service, userMessages(userMessage));
    }

    private Object resolveAutoSkillActivation(String userMessage) throws Exception {
        Method method = AgentService.class.getDeclaredMethod("resolveAutoSkillActivation", ArrayNode.class);
        method.setAccessible(true);
        return method.invoke(service, userMessages(userMessage));
    }

    private Object invocationField(Object resolution, String fieldName) throws Exception {
        Field field = resolution.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(resolution);
    }

    @Test
    void detectsMacApplicationListingRequestsBeforeCallingTheModel() throws Exception {
        Object toolCall = detectToolCall("Eu quero uma listagem de todos os apps que tem no meu macOS.");

        assertToolCallName(toolCall, "list_macos_apps");
    }

    @Test
    void detectsAntigravityAsLocalApplication() throws Exception {
        Object toolCall = detectToolCall("Antigravity IDE cara, abre ele pra mim.");

        assertToolCall(toolCall, "open_app", "Antigravity IDE");
    }

    @Test
    void exposesExternalMcpToolsForWebSearchIntent() throws Exception {
        assertTrue(shouldExposeTool("browser_navigate", "Pesquisa sobre a arquitetura do Avento para mim."));
    }

    @Test
    void doesNotExposeExternalMcpToolsForUnrelatedProjectRequest() throws Exception {
        assertFalse(shouldExposeTool("browser_navigate", "Analisa esse projeto para mim."));
    }

    @Test
    void detectsNoisyVsCodeCloseRequestsBeforeCallingTheModel() throws Exception {
        Object toolCall = detectToolCall("FESHA, o app do Visual Studio Code.");

        assertToolCall(toolCall, "close_app", "Visual Studio Code");
    }

    @Test
    void detectsSpeechToTextVsCodeAliases() throws Exception {
        Object toolCall = detectToolCall("Fetcha o versículo hoje.");

        assertToolCall(toolCall, "close_app", "Visual Studio Code");
    }

    @Test
    void treatsShortAppMentionsAsOpenRequests() throws Exception {
        Object toolCall = detectToolCall("VSC Code app para mim.");

        assertToolCall(toolCall, "open_app", "Visual Studio Code");
    }

    @Test
    void treatsNoisyShortVsCodeMentionsAsOpenRequests() throws Exception {
        Object toolCall = detectToolCall("Minho VS Code.");

        assertToolCall(toolCall, "open_app", "Visual Studio Code");
    }

    @Test
    void detectsBraveAsLocalApplication() throws Exception {
        Object toolCall = detectToolCall("Vaba dentro do navegador Brave.");

        assertToolCall(toolCall, "open_app", "Brave Browser");
    }

    @Test
    void detectsPortugueseBraveNewTabRequestsBeforeCallingTheModel() throws Exception {
        Object toolCall = detectToolCall("A nova aba no Brave.");

        assertToolCallArgument(toolCall, "open_browser_tab", "browserName", "Brave Browser");
    }

    @Test
    void detectsNoisyPortugueseBraveNewTabRequestsBeforeCallingTheModel() throws Exception {
        Object toolCall = detectToolCall("Abre para mim uma nova água no navegador Brave.");

        assertToolCallArgument(toolCall, "open_browser_tab", "browserName", "Brave Browser");
    }

    @Test
    void detectsEnglishBraveNewPageRequestsBeforeCallingTheModel() throws Exception {
        Object toolCall = detectToolCall("New page on Brave browser.");

        assertToolCallArgument(toolCall, "open_browser_tab", "browserName", "Brave Browser");
    }

    @Test
    void closesBrowserTabInsteadOfWholeBrowserForTabRequests() throws Exception {
        Object toolCall = detectToolCall("Fecha a aba da pesquisa no Brave.");

        assertToolCallArgument(toolCall, "close_browser_tab", "browserName", "Brave Browser");
    }

    @Test
    void stillClosesWholeBrowserWhenUserAsksForTheApp() throws Exception {
        Object toolCall = detectToolCall("Fecha o Brave.");

        assertToolCall(toolCall, "close_app", "Brave Browser");
    }

    @Test
    void handlesStopFrustrationWithoutCallingTheModel() throws Exception {
        String response = detectDirectConversationResponse("Chega, chega, calabou, cata falando merda já.");

        assertEquals("Tá. Vou ficar quieto agora.\n", response);
    }

    @Test
    void passesNoisyGoodNightToTheModel() throws Exception {
        String response = detectDirectConversationResponse("Od si boho noici.");

        assertNull(response);
    }

    @Test
    void passesShortGreetingToTheModel() throws Exception {
        String response = detectDirectConversationResponse("oi");

        assertNull(response);
    }

    @Test
    void passesContextWrappedShortGreetingToTheModel() throws Exception {
        String response = detectDirectConversationResponse("""
                [Local Environment]
                OS: macOS

                Com base no contexto local acima, responda ao seguinte pedido do usuário. Não diga que você não tem acesso ao sistema quando [Local Environment] estiver presente; use esse contexto e seja específico:

                Bom dia!

                oi
                """);

        assertNull(response);
    }

    @Test
    void detectsRequestedAppInsteadOfFinderWhenEnvironmentContextListsAppsFirst() throws Exception {
        Object toolCall = detectOptionalToolCall("""
                [Local Environment]
                OS: macOS
                Apps detectados: Finder, Terminal, Visual Studio Code, Brave Browser, Safari

                Com base no contexto local acima, responda ao seguinte pedido do usuário. Não diga que você não tem acesso ao sistema quando [Workspace Roots], [Local Environment] ou [Project Analysis] estiverem presentes; use esse contexto e seja específico:

                Abre uma nova aba no navegador Brave.
                """);

        assertToolCallArgument(toolCall, "open_browser_tab", "browserName", "Brave Browser");
    }

    @Test
    void doesNotTreatWordsContainingOiAsShortGreeting() throws Exception {
        String response = detectDirectConversationResponse("foi isso");

        assertNull(response);
    }

    @Test
    void passesNoisyHowAreYouToTheModelWithoutOpeningFinder() throws Exception {
        String response = detectDirectConversationResponse("Comervais?");
        Object toolCall = detectOptionalToolCall("Comervais?");

        assertNull(response);
        assertNull(toolCall);
    }

    @Test
    void doesNotCloseFinderFromNoisyCasualVoiceCorrection() throws Exception {
        Object toolCall = detectOptionalToolCall("Doitia, fecha, como vai Finder?");

        assertNull(toolCall);
    }

    @Test
    void passesCapabilityQuestionToTheModelWithoutOpeningFinder() throws Exception {
        String response = detectDirectConversationResponse("Avento, fala para mim o que mais você pode fazer.");
        Object toolCall = detectOptionalToolCall("Avento, fala para mim o que mais você pode fazer.");

        assertNull(response);
        assertNull(toolCall);
    }

    @Test
    void passesHelpQuestionToTheModelWithoutOpeningFinder() throws Exception {
        String response = detectDirectConversationResponse("Avento, fala para mim com o que você pode me ajudar.");
        Object toolCall = detectOptionalToolCall("Avento, fala para mim com o que você pode me ajudar.");

        assertNull(response);
        assertNull(toolCall);
    }

    @Test
    void passesAbbreviatedHelpQuestionToTheModel() throws Exception {
        String response = detectDirectConversationResponse("Com o que vc pode me ajudar?");

        assertNull(response);
    }

    @Test
    void passesIdentityQuestionToTheModelWithoutOpeningFinder() throws Exception {
        String message = "Avento. Explica para o meu amigo quem é você?";
        String response = detectDirectConversationResponse(message);
        Object toolCall = detectOptionalToolCall(message);

        assertNull(response);
        assertNull(toolCall);
    }

    @Test
    void ignoresWhisperPromptEchoWithoutOpeningFinder() throws Exception {
        String message = "Portugues brasileiro natural. Portugues brasileiro natural.";
        String response = detectDirectConversationResponse(message);
        Object toolCall = detectOptionalToolCall(message);

        assertNull(toolCall);
        org.assertj.core.api.Assertions.assertThat(response).contains("português brasileiro");
    }

    @Test
    void doesNotTreatGoodNightAsFinderAutomation() throws Exception {
        Object toolCall = detectOptionalToolCall("Boa noite, chute-se Finder.");

        assertNull(toolCall);
    }

    @Test
    void passesGoodNightToTheModelWithoutOpeningApps() throws Exception {
        String response = detectDirectConversationResponse("Eu disse boa noite.");
        Object toolCall = detectOptionalToolCall("Eu disse boa noite Finder.");

        assertNull(response);
        assertNull(toolCall);
    }

    private Object detectToolCall(String content) throws Exception {
        Object toolCall = detectOptionalToolCall(content);
        assertNotNull(toolCall);
        return toolCall;
    }

    private Object detectImageGenerationToolCall(String content) throws Exception {
        return detectImageGenerationToolCall(userMessages(content));
    }

    private Object detectImageGenerationToolCall(ArrayNode messages) throws Exception {
        Method detector = AgentService.class.getDeclaredMethod("detectDirectImageGenerationRequest", ArrayNode.class);
        detector.setAccessible(true);
        Object toolCall = detector.invoke(service, messages);
        assertNotNull(toolCall);
        return toolCall;
    }

    private String directToolCompletionMessage(Object toolCall, JsonNode toolResult) throws Exception {
        Method method = AgentService.class.getDeclaredMethod(
                "directToolCompletionMessage", toolCall.getClass(), JsonNode.class);
        method.setAccessible(true);
        return (String) method.invoke(service, toolCall, toolResult);
    }

    private Object detectOptionalToolCall(String content) throws Exception {
        ArrayNode messages = userMessages(content);

        Method detector = AgentService.class.getDeclaredMethod("detectDirectSystemAutomationRequest", ArrayNode.class);
        detector.setAccessible(true);
        return detector.invoke(service, messages);
    }

    private String detectDirectConversationResponse(String content) throws Exception {
        ArrayNode messages = userMessages(content);

        Method detector = AgentService.class.getDeclaredMethod("detectDirectConversationResponse", ArrayNode.class);
        detector.setAccessible(true);
        return (String) detector.invoke(service, messages);
    }

    private boolean shouldExposeTool(String toolName, String message) throws Exception {
        String normalized = MessageText.normalizeIntentText(message);

        Field intentRouterField = AgentService.class.getDeclaredField("intentRouter");
        intentRouterField.setAccessible(true);
        IntentRouter router = (IntentRouter) intentRouterField.get(service);
        IntentProfile profile = router.classify(normalized);

        Class<?> stateClass = Class.forName("com.avento.service.AgentService$AgentRunState");
        var stateCtor = stateClass.getDeclaredConstructor();
        stateCtor.setAccessible(true);
        Object state = stateCtor.newInstance();

        Method method = AgentService.class.getDeclaredMethod(
                "shouldExposeTool", String.class, String.class, IntentProfile.class, stateClass);
        method.setAccessible(true);
        return (boolean) method.invoke(service, toolName, normalized, profile, state);
    }

    private ObjectNode buildOllamaRequest(String model, ArrayNode messages) throws Exception {
        Method method =
                AgentService.class.getDeclaredMethod("buildOllamaRequest", String.class, ArrayNode.class, List.class);
        method.setAccessible(true);
        return (ObjectNode) method.invoke(service, model, messages, List.of());
    }

    private ArrayNode userMessages(String content) {
        ArrayNode messages = mapper.createArrayNode();
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", content);
        return messages;
    }

    private ArrayNode userMessages(String firstContent, String secondContent) {
        ArrayNode messages = userMessages(firstContent);
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", secondContent);
        return messages;
    }

    private ArrayNode userMessages(String firstContent, String secondContent, String thirdContent) {
        ArrayNode messages = userMessages(firstContent, secondContent);
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", thirdContent);
        return messages;
    }

    private ArrayNode userMessagesWithImage(String content) {
        ArrayNode messages = userMessages(content);
        ((ObjectNode) messages.get(0)).putArray("images").add("fake-base64-image");
        return messages;
    }

    private void assertToolCall(Object toolCall, String expectedName, String expectedAppName) throws Exception {
        assertToolCallArgument(toolCall, expectedName, "appName", expectedAppName);
    }

    private void assertToolCallName(Object toolCall, String expectedName) throws Exception {
        Method nameMethod = toolCall.getClass().getDeclaredMethod("name");
        nameMethod.setAccessible(true);

        assertEquals(expectedName, nameMethod.invoke(toolCall));
    }

    private void assertToolCallArgument(Object toolCall, String expectedName, String argumentName, String expectedValue)
            throws Exception {
        Method nameMethod = toolCall.getClass().getDeclaredMethod("name");
        Method argumentsMethod = toolCall.getClass().getDeclaredMethod("arguments");
        nameMethod.setAccessible(true);
        argumentsMethod.setAccessible(true);

        assertEquals(expectedName, nameMethod.invoke(toolCall));
        JsonNode arguments = (JsonNode) argumentsMethod.invoke(toolCall);
        assertEquals(expectedValue, arguments.path(argumentName).asText());
    }
}
