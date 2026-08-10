package com.avento.service.execution;

import com.avento.service.intent.ImageIntentService;
import com.avento.service.support.HeuristicWordLists;
import com.avento.service.support.MessageText;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.node.ArrayNode;

/**
 * Politica de FIM DE TURNO: decide se a rodada merece nova tentativa, se merece aviso, e se um
 * pedido casual deve ignorar as ferramentas que o modelo pediu.
 *
 * <p><b>Por que classe propria:</b> muda por um motivo so — comportamento de falha e de repeticao.
 * Nao muda quando muda o catalogo de ferramentas, o transporte do modelo ou a politica de permissao.
 *
 * <p><b>Politica, nao efeito.</b> Tudo aqui responde uma pergunta e nao faz nada: quem emite evento,
 * executa ferramenta e recursa continua sendo o laco do agente. Essa separacao e o que permite testar
 * a decisao sem um {@code FluxSink} e sem estado mutavel.
 *
 * <p>Extraido do {@code AgentService} sem mudanca de comportamento: os metodos privados de la viraram
 * delegadores, e os testes que os alcancam por reflexao continuam passando sem alteracao.
 */
public class TurnEndPolicy {

    /**
     * O que a decisao precisa saber da run, por VALOR.
     *
     * <p>O {@code AgentRunState} e mutado por dezenas de metodos ao longo do turno. Receber copia dos
     * tres campos que importam e o que torna estas respostas reproduziveis.
     */
    public record TurnContext(int executedToolCalls, boolean retriedWithFullToolset, boolean forceFullToolset) {}

    private final ImageIntentService imageIntentService;

    public TurnEndPolicy(ImageIntentService imageIntentService) {
        this.imageIntentService = imageIntentService;
    }

    private static final Set<String> PROJECT_ACTION_WORDS =
            Set.copyOf(HeuristicWordLists.loadLines("agent/heuristics/project-action-words.txt"));

    // Verbos de acao que so se cumprem com ferramenta. "vou explicar" nao entra: e coisa que o
    // modelo faz em texto mesmo.
    private static final Pattern ANNOUNCED_ACTION = Pattern.compile(
            "\\b(vou|irei|deixa\\s+eu|deixe-me|estou)\\s+(?:\\w+\\s+)?"
                    + "(pesquisar|pesquisando|buscar|buscando|procurar|procurando|acessar|acessando|"
                    + "consultar|consultando|verificar|verificando|ler|lendo|baixar|baixando|"
                    + "executar|executando|rodar|rodando|abrir|abrindo|analisar|analisando)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Limite acima do qual o texto ja e uma resposta de verdade, nao um preambulo vazio. */
    private static final int ANNOUNCEMENT_MAX_CHARS = 900;

    // Rede de seguranca contra falso negativo do filtro de intencao (Opcao 2):
    // se a primeira rodada nao chamou nenhuma ferramenta para uma mensagem que
    // nao e conversa casual, tenta de novo uma unica vez com todas as
    // ferramentas visiveis, em vez de assumir que o modelo decidiu nao agir.
    public boolean shouldRetryWithFullToolset(TurnContext context, int round, ArrayNode messages) {
        if (round != 1 || context.retriedWithFullToolset() || context.forceFullToolset()) {
            return false;
        }
        String lastUserMessage = MessageText.lastUserMessage(messages);
        if (lastUserMessage == null || lastUserMessage.contains("[Project Analysis]")) {
            return false;
        }
        String normalized = MessageText.normalizeIntentText(MessageText.extractDirectUserRequest(lastUserMessage));
        return isActionableToolRequest(normalized);
    }

    // context.executedToolCalls() só sobe quando uma ferramenta é de fato
    // executada (nunca em aprovação rejeitada) — é a única fonte confiável
    // pra saber se "algo aconteceu de verdade" nesta resposta, ao contrário
    // do texto do modelo, que pode alegar sucesso sem ter feito nada.
    public boolean shouldWarnAboutNoToolExecution(TurnContext context, ArrayNode messages) {
        if (context.executedToolCalls() > 0) {
            return false;
        }
        String lastUserMessage = MessageText.lastUserMessage(messages);
        if (lastUserMessage == null) {
            return false;
        }
        String normalized = MessageText.normalizeIntentText(MessageText.extractDirectUserRequest(lastUserMessage));
        return isActionableToolRequest(normalized);
    }

    public boolean isActionableToolRequest(String normalizedMessage) {
        if (normalizedMessage == null || normalizedMessage.isBlank()) {
            return false;
        }
        if (imageIntentService.wantsImageGeneration(normalizedMessage)
                || com.avento.service.tools.AgentToolSelector.wantsScreenCapture(normalizedMessage)) {
            return true;
        }
        for (String actionWord : PROJECT_ACTION_WORDS) {
            if (normalizedMessage.contains(actionWord)) {
                return true;
            }
        }
        return false;
    }

    public boolean shouldIgnoreToolCallsForCasualMessage(ArrayNode messages) {
        String lastUserMessage = MessageText.lastUserMessage(messages);
        if (lastUserMessage == null) {
            return false;
        }
        return MessageText.isCasualUserMessage(MessageText.extractDirectUserRequest(lastUserMessage));
    }

    /**
     * Detecta a rodada em que o modelo ANUNCIA uma acao e nao executa nada.
     *
     * <p>A guarda de turno vazio nao pegava isto: ela exige texto em branco, e aqui o modelo escreve
     * "Vou pesquisar agora!" e encerra. Para o usuario e pior que o silencio — parece que algo esta
     * acontecendo. Observado quatro vezes seguidas, duas delas repetindo o mesmo paragrafo palavra
     * por palavra, com a ferramenta disponivel na mesa.
     *
     * <p>So vale quando havia ferramenta para chamar: sem toolset, prometer e a unica saida.
     */
    public static boolean announcedActionWithoutCalling(
            String assistantText, boolean roundCalledATool, boolean toolsAvailable) {
        if (roundCalledATool || !toolsAvailable || assistantText == null) {
            return false;
        }
        String text = assistantText.trim();
        if (text.isEmpty() || text.length() > ANNOUNCEMENT_MAX_CHARS) {
            return false;
        }
        return ANNOUNCED_ACTION.matcher(text).find();
    }
}
