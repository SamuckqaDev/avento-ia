package com.avento.service;

import static com.avento.service.support.MessageText.containsAny;
import static com.avento.service.support.MessageText.extractDirectUserRequest;
import static com.avento.service.support.MessageText.isCasualUserMessage;
import static com.avento.service.support.MessageText.lastUserMessage;
import static com.avento.service.support.MessageText.normalizeIntentText;

import com.avento.service.support.HeuristicWordLists;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Monta o system prompt de uma rodada.
 *
 * <h2>A regra que não pode ser quebrada aqui</h2>
 *
 * <p>O prefixo deste texto tem de ser ESTÁVEL entre requisições. O cache de prompt do Ollama casa
 * por prefixo de tokens: se o começo muda, ele reavalia os ~8192 tokens inteiros de novo. Um
 * {@code LocalDateTime.now()} no bloco de ambiente — com hora e nanossegundo — mudava a cada
 * chamada e custava cerca de 50 segundos por resposta, com rodadas de 70 a 87 segundos gerando dois
 * ou três mil caracteres. Trocado por {@link LocalDate}, o prefixo fica igual o dia todo e a
 * avaliação de contexto cai de 4,0s para 0,1s a partir da segunda mensagem.
 *
 * <p>{@code PromptPrefixStabilityTest} trava isso. Um benchmark isolado NÃO pega esse defeito,
 * porque manda sempre o mesmo prompt e portanto sempre acerta o cache — só o uso real, que muda o
 * relógio a cada chamada, expõe o problema.
 *
 * <p>Ao acrescentar qualquer coisa ao prompt: nada volátil no prefixo.
 */
@Service
public class PromptAssemblyService {

    private static final Logger logger = LoggerFactory.getLogger(PromptAssemblyService.class);

    private static final String AGENT_SYSTEM_PROMPT = loadAgentInstructions();
    private static final String AVENTO_PRODUCT_FACTS = loadAgentResource("agent/instructions/product.md");

    private static final Map<String, List<String>> BLOCK_TERMS =
            HeuristicWordLists.loadSections("agent/heuristics/prompt-blocks.txt");

    /** Quantos turnos para trás procurar uma pergunta sobre o próprio Avento. */
    private static final int PRODUCT_QUESTION_LOOKBACK = 8;

    private static final int CONTINUITY_REQUEST_MAX_CHARS = 1_200;

    @Value("${avento.agent.policy-mode:maximum}")
    private String policyMode;

    @Value("${avento.agent.policy-override-dir:}")
    private String policyOverrideDirectory;

    // Opcional: os testes constroem pelo construtor, sem contexto Spring.
    @Autowired(required = false)
    private UserMemoryService userMemoryService;

    public void setUserMemoryService(UserMemoryService userMemoryService) {
        this.userMemoryService = userMemoryService;
    }

    public void setPolicyMode(String policyMode) {
        this.policyMode = policyMode;
    }

    public void setPolicyOverrideDirectory(String policyOverrideDirectory) {
        this.policyOverrideDirectory = policyOverrideDirectory;
    }

    /**
     * O conteúdo da mensagem {@code system} da rodada.
     *
     * <p>A ordem dos blocos é o prefixo cacheável: instruções fixas primeiro, e o que varia por
     * conversa (memória, continuidade) por último.
     */
    public String systemPrompt(ArrayNode messages, List<String> workspaceRoots, UUID userId) {
        return AGENT_SYSTEM_PROMPT
                + productFactsBlock(messages)
                + "\n\n[Local Environment]\nData atual: "
                + LocalDate.now()
                + policyInstructions()
                + workspaceRootsBlock(workspaceRoots)
                + memoryBlock(userId)
                + conversationContinuityBlock(messages);
    }

    /** Os fatos do produto só entram quando a conversa pergunta sobre o próprio Avento. */
    private String productFactsBlock(ArrayNode messages) {
        int start = Math.max(0, messages.size() - PRODUCT_QUESTION_LOOKBACK);
        for (int index = messages.size() - 1; index >= start; index--) {
            String content = messages.get(index).path("content").asText("");
            String normalized = normalizeIntentText(extractDirectUserRequest(content));
            if (containsAny(normalized, terms("PRODUCT_QUESTION"))) {
                return "\n\n" + AVENTO_PRODUCT_FACTS;
            }
        }
        return "";
    }

    /** Só os fatos ACTIVE pertencentes ao usuário autenticado. */
    private String memoryBlock(UUID userId) {
        if (userId == null || userMemoryService == null) {
            return "";
        }
        try {
            String block = userMemoryService.promptBlock(userId);
            if (block != null && !block.isBlank()) {
                logger.debug("Bloco de memória injetado para o usuário {} ({} caracteres)", userId, block.length());
            }
            return block == null ? "" : block;
        } catch (RuntimeException exception) {
            logger.debug("Não foi possível montar o bloco de memória; seguindo sem ele", exception);
            return "";
        }
    }

    private String policyInstructions() {
        String mode = policyMode == null ? "maximum" : policyMode.trim().toLowerCase(Locale.ROOT);
        String resource =
                switch (mode) {
                    case "professional" -> "agent/policies/professional.md";
                    case "protected" -> "agent/policies/protected.md";
                    default -> "agent/policies/maximum.md";
                };
        return "\n\n" + localPolicyOverride(mode).orElseGet(() -> loadPolicyResource(resource));
    }

    /** Política local do usuário, quando existir: experimentos ficam fora do repositório. */
    private Optional<String> localPolicyOverride(String mode) {
        if (policyOverrideDirectory == null || policyOverrideDirectory.isBlank()) {
            return Optional.empty();
        }

        Path overridePath =
                Paths.get(policyOverrideDirectory).resolve(mode + ".md").normalize();
        if (!Files.isRegularFile(overridePath)) {
            return Optional.empty();
        }

        try {
            logger.info("Using local agent policy override from {}", overridePath);
            return Optional.of(Files.readString(overridePath, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            logger.warn(
                    "Could not read local agent policy override at {}; using bundled policy", overridePath, exception);
            return Optional.empty();
        }
    }

    /**
     * As raízes autorizadas, escritas por extenso.
     *
     * <p>O prompt e as descrições das ferramentas de arquivo mandavam usar caminhos "dentro de
     * [Workspace Roots]", mas nada nunca mostrava ao modelo QUAIS eram — ele não tinha escolha
     * senão inventar um caminho plausível, que falhava na autorização em toda tentativa.
     */
    private String workspaceRootsBlock(List<String> workspaceRoots) {
        if (workspaceRoots == null || workspaceRoots.isEmpty()) {
            // Mencionar um workspace ausente ancora modelos pequenos numa limitação irrelevante, até
            // em conversa comum. As ferramentas de arquivo seguem exigindo autorização no backend.
            return "";
        }
        StringBuilder block = new StringBuilder("\n\n[Workspace Roots]\n");
        for (String root : workspaceRoots) {
            block.append("- ").append(root).append('\n');
        }
        block.append("Use SEMPRE um desses caminhos absolutos exatos (ou um arquivo dentro deles) como "
                + "argumento \"path\" das ferramentas de arquivo. NUNCA invente, adivinhe ou monte um "
                + "caminho parecido (ex: \"/Users/usuario/projetos/...\") — isso sempre falha. Se não "
                + "souber o caminho exato de um arquivo dentro da raiz, use directory_tree ou "
                + "search_files primeiro para descobrir.");
        return block.toString();
    }

    /** "continue" não diz o que continuar; recupera o último pedido com assunto próprio. */
    private String conversationContinuityBlock(ArrayNode messages) {
        String latestRequest = lastUserMessage(messages);
        if (latestRequest == null || !isGenericContinuationRequest(extractDirectUserRequest(latestRequest))) {
            return "";
        }

        boolean skippedLatest = false;
        for (int index = messages.size() - 1; index >= 0; index--) {
            JsonNode message = messages.get(index);
            if (!"user".equals(message.path("role").asText(""))) {
                continue;
            }
            if (!skippedLatest) {
                skippedLatest = true;
                continue;
            }
            String request =
                    extractDirectUserRequest(message.path("content").asText("")).trim();
            if (request.isBlank()
                    || isGenericContinuationRequest(request)
                    || isCasualUserMessage(normalizeIntentText(request))) {
                continue;
            }
            String compactRequest = request.length() <= CONTINUITY_REQUEST_MAX_CHARS
                    ? request
                    : request.substring(0, CONTINUITY_REQUEST_MAX_CHARS) + "...";
            return "\n\n[Conversation Continuity]\n"
                    + "A mensagem atual é uma continuação curta. Preserve este último pedido explícito como objetivo; "
                    + "não invente outro assunto:\n"
                    + compactRequest;
        }
        return "";
    }

    private boolean isGenericContinuationRequest(String request) {
        return containsAny(normalizeIntentText(request), terms("GENERIC_CONTINUATION"));
    }

    private static List<String> terms(String section) {
        return BLOCK_TERMS.getOrDefault(section, List.of());
    }

    private static String loadAgentInstructions() {
        List<String> resources = List.of(
                "agent/instructions/identity.md",
                "agent/instructions/personality.md",
                "agent/instructions/context.md",
                "agent/instructions/tools.md",
                "agent/instructions/execution.md");
        StringBuilder instructions = new StringBuilder();
        for (String resource : resources) {
            instructions.append(loadAgentResource(resource)).append("\n\n");
        }
        return instructions.toString().trim();
    }

    private static String loadAgentResource(String resource) {
        try {
            ClassPathResource file = new ClassPathResource(resource);
            try (var inputStream = file.getInputStream()) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível carregar a instrução do agente: " + resource, exception);
        }
    }

    private String loadPolicyResource(String resource) {
        try {
            ClassPathResource policyResource = new ClassPathResource(resource);
            try (var inputStream = policyResource.getInputStream()) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível carregar a política do agente: " + resource, exception);
        }
    }
}
