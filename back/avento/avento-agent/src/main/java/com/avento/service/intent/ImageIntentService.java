package com.avento.service.intent;

import static com.avento.service.support.MessageText.containsAny;
import static com.avento.service.support.MessageText.extractDirectUserRequest;
import static com.avento.service.support.MessageText.isCasualUserMessage;
import static com.avento.service.support.MessageText.lastUserMessage;
import static com.avento.service.support.MessageText.normalizeIntentText;

import com.avento.service.support.HeuristicWordLists;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Decide se a mensagem pede uma imagem, e com que prompt.
 *
 * <p>O ponto delicado é que existem dois julgamentos diferentes com o mesmo nome. Expor a ferramenta
 * de imagem ao modelo pode errar para mais — no pior caso o modelo ignora uma ferramenta a mais.
 * Já {@link #detectDirectImageGenerationRequest} PULA o modelo e chama {@code generate_image}
 * sozinho: aí só vale alta precisão, porque um falso positivo gera uma imagem que ninguém pediu.
 * Foi o que aconteceu com "faça uma análise dessa pasta" acionando geração de imagem.
 *
 * <p>Por isso o classificador por embedding, que é orientado a recall, continua valendo na exposição
 * de ferramentas (via {@code IntentRouter}) e não entra aqui.
 */
@Component
public class ImageIntentService {

    private static final Map<String, List<String>> SIGNALS =
            HeuristicWordLists.loadSections("agent/heuristics/image-prompt-signals.txt");

    private static final List<String> GENERATION_TRIGGERS = Stream.concat(
                    section("TRIGGERS_PT").stream(), section("TRIGGERS_EN").stream())
            .toList();

    /** Um prompt colado inteiro não traz verbo de pedido; reconhece-se pelo acúmulo de sinais. */
    private static final int STANDALONE_PROMPT_MIN_CHARS = 80;

    private static final int STANDALONE_MIN_STYLE_SIGNALS = 2;
    private static final int STANDALONE_MIN_COMPOSITION_SIGNALS = 2;

    private static final Pattern PREVIOUS_IMAGE_SUBJECT =
            Pattern.compile("(?iu)\\bimagem\\s+(?:do|da|de um|de uma)\\s+([^.!?]+)");

    private static final Pattern REQUEST_SUBJECT = Pattern.compile(
            "(?iu)^(?:gera|gere|faz|faça|faca|cria|crie)\\s+(?:o|a|um|uma)?\\s*(.+?)(?:\\s+que\\s+(?:eu\\s+)?pedi)?[.!?]*$");

    private final VisualIntentClassifier visualIntentClassifier;

    public ImageIntentService(VisualIntentClassifier visualIntentClassifier) {
        this.visualIntentClassifier = visualIntentClassifier;
    }

    public boolean wantsInterfacePrototype(String normalizedMessage) {
        return visualIntentClassifier.isInterfacePrototype(normalizedMessage);
    }

    public boolean wantsImageGeneration(String normalizedMessage) {
        if (wantsInterfacePrototype(normalizedMessage)) {
            return false;
        }
        return visualIntentClassifier.isProductMockup(normalizedMessage) || matchesGenerationTrigger(normalizedMessage);
    }

    /**
     * Estático e visível ao teste de propósito: a lista mora num .txt editável sem recompilar, então
     * {@code ImageGenerationTriggerTest} é a única coisa que impede alguém de apagar uma frase sem
     * perceber.
     */
    public static boolean matchesGenerationTrigger(String normalizedMessage) {
        if (normalizedMessage == null || normalizedMessage.isBlank()) {
            return false;
        }
        return GENERATION_TRIGGERS.stream().anyMatch(normalizedMessage::contains);
    }

    /**
     * A rodada vai produzir mídia, então a narração do modelo espera em vez de anunciar antes.
     *
     * <p>Cobre imagem e vídeo: o motivo é o mesmo nos dois casos.
     */
    public boolean shouldDeferMediaNarration(ArrayNode messages) {
        String userMessage = lastUserMessage(messages);
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String normalized = normalizeIntentText(extractDirectUserRequest(userMessage));
        return wantsImageGeneration(normalized) || containsAny(normalized, section("VIDEO_TRIGGERS"));
    }

    /** O atalho que pula o modelo. Alta precisão, não alto recall — ver o javadoc da classe. */
    public Optional<String> detectDirectImageGenerationRequest(ArrayNode messages) {
        String lastUserMessage = lastUserMessage(messages);
        if (lastUserMessage == null || lastUserMessage.isBlank()) {
            return Optional.empty();
        }

        String directRequest = extractDirectUserRequest(lastUserMessage);
        String normalized = normalizeIntentText(directRequest);

        boolean directImageRequest = wantsImageGeneration(normalized);
        boolean standaloneImagePrompt = looksLikeStandaloneImagePrompt(directRequest);
        boolean retryingPreviousImageRequest =
                isGenericFollowUp(directRequest) && hasPreviousImageGenerationContext(messages, directRequest);
        if (!directImageRequest && !standaloneImagePrompt && !retryingPreviousImageRequest) {
            return Optional.empty();
        }

        return Optional.of(imageGenerationPrompt(messages, directRequest));
    }

    /**
     * Um prompt de imagem colado direto, sem pedido em volta.
     *
     * <p>Precisa ser longo, não ser pergunta, não conter marca de discussão ("melhore o prompt",
     * "traduza") e acumular sinais de estilo E de composição. Cada uma dessas guardas existe porque
     * sem ela um texto que só FALA sobre imagem virava um pedido de gerar.
     */
    public boolean looksLikeStandaloneImagePrompt(String request) {
        if (request == null || request.length() < STANDALONE_PROMPT_MIN_CHARS || request.contains("?")) {
            return false;
        }
        String normalized = normalizeIntentText(request);
        if (countSignals(normalized, "DISCUSSION") > 0) {
            return false;
        }
        return countSignals(normalized, "VISUAL_STYLE") >= STANDALONE_MIN_STYLE_SIGNALS
                && countSignals(normalized, "COMPOSITION") >= STANDALONE_MIN_COMPOSITION_SIGNALS;
    }

    /** O prompt a usar: o próprio pedido, ou o de um turno anterior quando o pedido não tem assunto. */
    public String imageGenerationPrompt(ArrayNode messages, String directRequest) {
        String trimmedRequest = directRequest == null ? "" : directRequest.trim();
        if (!isGenericFollowUp(trimmedRequest)) {
            return trimmedRequest;
        }

        return previousUserPrompt(messages, false)
                .or(() -> previousAssistantImagePrompt(messages))
                .or(() -> imageSubjectPrompt(trimmedRequest))
                .orElse(trimmedRequest.isBlank() ? "Gere uma imagem a partir do pedido do usuário." : trimmedRequest);
    }

    public boolean isGenericFollowUp(String request) {
        return containsAny(normalizeIntentText(request), section("GENERIC_FOLLOW_UP"));
    }

    public boolean isImageGenerationStatusMessage(String normalizedMessage) {
        return containsAny(normalizedMessage, section("IMAGE_STATUS_MESSAGE"));
    }

    private boolean hasPreviousImageGenerationContext(ArrayNode messages, String directRequest) {
        return previousUserPrompt(messages, true).isPresent()
                || previousAssistantImagePrompt(messages).isPresent()
                || imageSubjectPrompt(directRequest).isPresent();
    }

    private Optional<String> previousUserPrompt(ArrayNode messages, boolean requireImageIntent) {
        for (int index = messages.size() - 2; index >= 0; index--) {
            JsonNode message = messages.get(index);
            if (!"user".equals(message.path("role").asText(""))) {
                continue;
            }
            String previousRequest =
                    extractDirectUserRequest(message.path("content").asText("")).trim();
            String normalizedPreviousRequest = normalizeIntentText(previousRequest);
            if (!previousRequest.isBlank()
                    && (!requireImageIntent || wantsImageGeneration(normalizedPreviousRequest))
                    && !isGenericFollowUp(previousRequest)
                    && !isImageGenerationStatusMessage(normalizedPreviousRequest)
                    && !isCasualUserMessage(normalizedPreviousRequest)) {
                return Optional.of(previousRequest);
            }
        }
        return Optional.empty();
    }

    /** Recupera o assunto do próprio aviso de falha que o Avento escreveu no turno anterior. */
    private Optional<String> previousAssistantImagePrompt(ArrayNode messages) {
        for (int index = messages.size() - 2; index >= 0; index--) {
            JsonNode message = messages.get(index);
            if (!"assistant".equals(message.path("role").asText(""))) {
                continue;
            }
            String content = message.path("content").asText("");
            if (!isImageGenerationStatusMessage(normalizeIntentText(content))) {
                continue;
            }
            Matcher matcher = PREVIOUS_IMAGE_SUBJECT.matcher(content);
            if (matcher.find()) {
                return Optional.of("Gere uma imagem de " + matcher.group(1).trim() + ".");
            }
        }
        return Optional.empty();
    }

    private Optional<String> imageSubjectPrompt(String directRequest) {
        String request = directRequest == null ? "" : directRequest.trim();
        Matcher subjectMatcher = REQUEST_SUBJECT.matcher(request);
        if (subjectMatcher.matches()) {
            String subject = subjectMatcher.group(1).trim();
            if (!subject.isBlank()
                    && !containsAny(normalizeIntentText(subject), "imagem", "o que", "isso", "de novo")) {
                return Optional.of("Gere uma imagem de " + subject + ".");
            }
        }
        return Optional.empty();
    }

    private long countSignals(String normalized, String sectionName) {
        return section(sectionName).stream().filter(normalized::contains).count();
    }

    private static List<String> section(String name) {
        return SIGNALS.getOrDefault(name, List.of());
    }
}
