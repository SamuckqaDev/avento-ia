package com.avento.service.tools;

import com.avento.service.intent.AgentIntent;
import com.avento.service.intent.ImageIntentService;
import com.avento.service.intent.IntentProfile;
import com.avento.service.intent.IntentRouter;
import com.avento.service.support.MessageText;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Decide QUAIS ferramentas o modelo enxerga nesta rodada.
 *
 * <p>Extraido do {@code AgentService} sem mudanca de comportamento: os testes de caracterizacao
 * escritos antes da extracao continuam batendo no metodo privado do {@code AgentService}, que agora
 * apenas delega para ca. Se algum deles falhar, a extracao mudou algo — e nao deveria.
 *
 * <p><b>Por que isto merece classe propria:</b> muda por um motivo so — catalogo de ferramentas ou
 * politica de custo. Nao muda quando muda o transporte do modelo, a politica de permissao ou o
 * tratamento de falha do turno, que sao os outros motivos de mudanca do laco do agente.
 *
 * <p>A decisao aqui custa caro dos dois lados: ferramenta de menos e o turno termina vazio (o modelo
 * "planeja" no thinking e nao age); ferramenta demais e o custo de {@code prompt_eval} estoura o
 * timeout da run — medido ao vivo, 23 ferramentas levaram uma rodada a passar de 6 minutos sem
 * sinal algum.
 */
public class AgentToolSelector {

    /**
     * O que a selecao precisa saber da run, sem depender do estado MUTAVEL do laco.
     *
     * <p>O {@code AgentRunState} e alterado por dezenas de metodos ao longo do turno; receber os
     * campos por valor aqui e o que torna esta decisao reproduzivel — mesma entrada, mesma saida.
     */
    public record SelectionContext(
            List<String> workspaceRoots,
            Set<String> requiredToolNames,
            String requiredToolName,
            Set<String> extraExposedToolNames,
            boolean forceFullToolset) {}

    private final ObjectMapper mapper;
    private final IntentRouter intentRouter;
    private final ImageIntentService imageIntentService;
    private final Set<String> projectToolkit;
    private final boolean exposeAllTools;
    private final int maxToolsPerRequest;
    private final int maxProjectTools;
    private final int projectToolkitExtraLimit;

    public AgentToolSelector(
            ObjectMapper mapper,
            IntentRouter intentRouter,
            ImageIntentService imageIntentService,
            Set<String> projectToolkit,
            boolean exposeAllTools,
            int maxToolsPerRequest,
            int maxProjectTools,
            int projectToolkitExtraLimit) {
        this.mapper = mapper;
        this.intentRouter = intentRouter;
        this.imageIntentService = imageIntentService;
        this.projectToolkit = projectToolkit;
        this.exposeAllTools = exposeAllTools;
        this.maxToolsPerRequest = maxToolsPerRequest;
        this.maxProjectTools = maxProjectTools;
        this.projectToolkitExtraLimit = projectToolkitExtraLimit;
    }

    public ArrayNode select(ArrayNode tools, ArrayNode messages, SelectionContext context) {
        ArrayNode selectedTools = mapper.createArrayNode();
        if (tools == null || tools.isEmpty()) {
            return selectedTools;
        }

        // Skill ativa com `Ferramenta:` declarada: a ferramenta dela e a resposta, ponto.
        // As heuristicas de keyword abaixo ja roubaram pedido de video pro generate_image;
        // a declaracao explicita da skill nao pode perder pra elas.
        if (context.requiredToolNames() != null && !context.requiredToolNames().isEmpty()) {
            ArrayNode required = filterToolsByName(tools, context.requiredToolNames());
            if (!required.isEmpty()) {
                return required;
            }
        }

        String lastUserMessage = MessageText.lastUserMessage(messages);
        if (lastUserMessage == null || lastUserMessage.isBlank()) {
            return selectedTools;
        }

        String normalized = MessageText.normalizeIntentText(MessageText.extractDirectUserRequest(lastUserMessage));
        if (MessageText.isCasualUserMessage(normalized)) {
            return selectedTools;
        }

        // Chat com projeto conectado usa um kit FIXO de ferramentas de desenvolvimento em vez
        // de selecao por intencao. Dois motivos, ambos aprendidos em producao: (1) a selecao
        // por mensagem errava — um pedido de apagar arquivo chegou ao modelo sem delete_file
        // e o turno terminou vazio; (2) a lista variando a cada mensagem muda o prefixo do
        // prompt e quebra o cache de prompt do llama.cpp, forcando reprocessar sistema +
        // schemas toda vez. Kit estavel = ferramentas sempre presentes + prefixo cacheavel.
        // Mockup/tela/interface = bloco ui-preview (HTML), nao ferramenta. Devolve conjunto vazio
        // para o modelo escrever o preview direto — e, crucial, para que generate_image nao fique
        // exposto e o modelo nao caia em gerar uma imagem feia e cara no lugar do preview.
        if (imageIntentService.wantsInterfacePrototype(normalized)) {
            return selectedTools;
        }
        // Pedido de imagem/captura PRIORIZA a ferramenta correspondente, mas nao exclui as demais:
        // o retorno exclusivo anterior quebrava pedidos compostos ("gera uma imagem E faz um pdf
        // dela" ficava so com generate_image). A prioridade garante a ferramenta certa no topo e
        // dentro do teto; o resto da selecao continua valendo.
        Set<String> priorityTools = new HashSet<>();
        if (imageIntentService.wantsImageGeneration(normalized)) {
            priorityTools.add("generate_image");
        }
        if (wantsScreenCapture(normalized)) {
            priorityTools.add("capture_screen");
        }
        if (!context.workspaceRoots().isEmpty()) {
            // Kit fixo primeiro (prefixo estavel pro cache de prompt), e ate 6 extras que a
            // intencao da mensagem pedir explicitamente — "conecta o mcp do git", "gera uma
            // imagem", "cria um projeto vite" trazem a ferramenta correspondente junto sem
            // abrir mao da estabilidade nas mensagens puras de codigo (que nao ativam extra
            // nenhum e mantem o payload identico ao da mensagem anterior).
            ArrayNode kit = filterToolsByName(tools, projectToolkit);
            IntentProfile intentProfile = intentRouter.classify(normalized);
            int extras = 0;
            for (JsonNode tool : tools) {
                String name = tool.path("name").asText("");
                if (projectToolkit.contains(name)) {
                    continue;
                }
                // Prioridade, auto-conectadas e ativadas via activate_tools entram SEMPRE
                // (fora do limite de extras): o modelo pediu por elas explicitamente.
                if (priorityTools.contains(name)
                        || context.extraExposedToolNames().contains(name)) {
                    kit.add(tool);
                    continue;
                }
                if (extras < projectToolkitExtraLimit && intentRouter.shouldExposeTool(name, intentProfile)) {
                    kit.add(tool);
                    extras++;
                }
            }
            // Este ramo devolvia o kit SEM teto nenhum: o limite de extras acima nao alcanca as
            // ativadas por activate_tools nem as fixadas, que entram sempre. Numa run longa elas se
            // acumulam em Redis rodada apos rodada — medido em producao: rodada 3 saiu com 22
            // schemas, contra o teto declarado de 12, e cada schema extra custa tempo em TODA rodada
            // seguinte. O kit do projeto continua inteiro (e a razao de ser deste ramo); o que passa
            // a ter fim e o crescimento em cima dele.
            return capProjectKit(kit, projectToolkit);
        }

        // classify() dispara uma chamada de embedding; calcular uma vez aqui e
        // reusar no loop evita uma chamada por ferramenta (dezenas de chamadas
        // redundantes ao Ollama para a mesma mensagem com MCP externo conectado).
        IntentProfile intentProfile = intentRouter.classify(normalized);
        // Sob o teto, as locais (inicio do catalogo) enchem as vagas e ferramentas externas de
        // intencao escassa nunca entram — medido ao vivo: "Executa a pesquisa" casou WEB, mas as
        // 12 vagas foram para filesystem e o fetch ficou de fora (o modelo precisou de 2 rodadas
        // de descoberta para alcanca-lo). O leitor web e A ferramenta da intencao WEB: prioridade.
        if (intentProfile.has(AgentIntent.WEB)) {
            priorityTools.add("fetch");
        }
        if (intentProfile.has(AgentIntent.DOCUMENT)) {
            priorityTools.add("generate_pdf");
        }
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            if (priorityTools.contains(name)
                    || context.extraExposedToolNames().contains(name)
                    || shouldExpose(name, normalized, intentProfile, context)) {
                selectedTools.add(tool);
            }
        }
        return capToolCount(selectedTools, priorityTools, context.extraExposedToolNames());
    }

    ArrayNode filterToolsByName(ArrayNode tools, Set<String> allowedNames) {
        ArrayNode filtered = mapper.createArrayNode();
        for (JsonNode tool : tools) {
            if (allowedNames.contains(tool.path("name").asText(""))) {
                filtered.add(tool);
            }
        }
        return filtered;
    }

    // Um esquema de ferramenta por si so e barato, mas 20+ deles somados ao prompt de
    // sistema empurram o custo de prompt_eval a ponto de uma rodada nunca terminar dentro
    // do timeout de inatividade da run — medido ao vivo: 23 ferramentas selecionadas levaram
    // uma rodada a exceder 6 minutos sem sinal algum, enquanto o mesmo pedido com poucas
    // ferramentas fecha em menos de 90s.
    //
    // Quando o teto forca uma escolha, as ferramentas casadas com a INTENCAO da tarefa
    // entram primeiro e as ALWAYS_EXPOSED preenchem o que sobrar — nao o contrario. As
    // sempre-expostas sao 10; dando prioridade a elas sobravam so 2 vagas, e um pedido
    // de apagar arquivo chegou ao modelo sem delete_file/edit_file/terminal_run: ele
    // "planejou" a acao no thinking e terminou o turno sem conseguir agir.
    /**
     * Teto do ramo de projeto conectado, onde o kit e fixo e o resto pode crescer sem parar.
     *
     * <p>O kit inteiro sobrevive — expo-lo e o proposito deste ramo. O corte cai sobre o que foi
     * acrescentado em cima dele, na ordem em que veio, para que uma run longa nao termine mandando o
     * dobro de schemas que a configuracao declara.
     */
    ArrayNode capProjectKit(ArrayNode kit, Set<String> projectToolkit) {
        if (exposeAllTools || kit.size() <= maxProjectTools) {
            return kit;
        }
        ArrayNode capped = mapper.createArrayNode();
        for (JsonNode tool : kit) {
            if (projectToolkit.contains(tool.path("name").asText(""))) {
                capped.add(tool);
            }
        }
        for (JsonNode tool : kit) {
            if (capped.size() >= maxProjectTools) {
                break;
            }
            if (!projectToolkit.contains(tool.path("name").asText(""))) {
                capped.add(tool);
            }
        }
        return capped;
    }

    ArrayNode capToolCount(ArrayNode selectedTools, Set<String> priorityTools, Set<String> extraExposed) {
        // No modo "mostra tudo" nao ha teto: o objetivo e justamente nao esconder ferramenta.
        if (exposeAllTools) {
            return selectedTools;
        }
        if (selectedTools.size() <= maxToolsPerRequest) {
            return selectedTools;
        }
        // Tres faixas, na ordem: (1) prioridade explicita da mensagem + auto-conectadas/ativadas,
        // (2) casadas com a intencao, (3) ALWAYS_EXPOSED preenchendo o que sobrar.
        ArrayNode capped = mapper.createArrayNode();
        Set<String> added = new HashSet<>();
        for (JsonNode tool : selectedTools) {
            String name = tool.path("name").asText("");
            if (capped.size() >= maxToolsPerRequest) {
                break;
            }
            if (priorityTools.contains(name) || extraExposed.contains(name)) {
                capped.add(tool);
                added.add(name);
            }
        }
        for (JsonNode tool : selectedTools) {
            String name = tool.path("name").asText("");
            if (capped.size() >= maxToolsPerRequest) {
                break;
            }
            if (!added.contains(name) && !ALWAYS_EXPOSED_TOOLS.contains(name)) {
                capped.add(tool);
                added.add(name);
            }
        }
        for (JsonNode tool : selectedTools) {
            String name = tool.path("name").asText("");
            if (capped.size() >= maxToolsPerRequest) {
                break;
            }
            if (!added.contains(name) && ALWAYS_EXPOSED_TOOLS.contains(name)) {
                capped.add(tool);
                added.add(name);
            }
        }
        return capped;
    }

    // Ferramentas baratas (schema pequeno) e de alto valor ficam sempre visíveis
    // ao modelo, em vez de dependerem de detecção de intenção por palavra-chave.
    // O filtro por intenção existe para conter o custo de contexto dos clusters
    // grandes de MCP externo (Git, Chrome DevTools etc.), não para ferramentas
    // isoladas como esta, cujo custo de sempre expor é desprezível.
    public static final Set<String> ALWAYS_EXPOSED_TOOLS = Set.of(
            "generate_image",
            "generate_video",
            "capture_screen",
            "read_document",
            "list_mcp_servers",
            "connect_mcp_server",
            "sequentialthinking",
            "read_graph",
            "search_nodes",
            "open_nodes",
            // O par de descoberta progressiva precisa estar SEMPRE na mesa: é a porta de entrada
            // para qualquer capacidade fora do toolset atual ("procura a ferramenta e usa").
            "search_capabilities",
            "activate_tools");

    public boolean shouldExpose(
            String toolName, String normalizedMessage, IntentProfile intentProfile, SelectionContext context) {
        // Modo "mostra tudo": entrega o toolset inteiro ao modelo sem triagem por intencao. Viavel
        // agora que o cache de prompt volta a funcionar (schemas ficam no prefixo cacheado e sao
        // avaliados uma vez, nao a cada mensagem). Custo: prompt maior e mais chance de o modelo
        // pequeno escolher errado. Ligar/desligar por AVENTO_AGENT_EXPOSE_ALL_TOOLS.
        if (exposeAllTools) {
            return true;
        }
        if (ALWAYS_EXPOSED_TOOLS.contains(toolName)) {
            return true;
        }

        if ("find_local_project".equals(toolName)) {
            return wantsLocalProjectDiscovery(normalizedMessage);
        }

        if (context.forceFullToolset()) {
            return true;
        }

        if (!context.requiredToolName().isEmpty() && context.requiredToolName().equals(toolName)) {
            return true;
        }

        if (!context.requiredToolNames().isEmpty()
                && context.requiredToolNames().contains(toolName)) {
            return true;
        }

        if (imageIntentService.wantsImageGeneration(normalizedMessage)) {
            return "generate_image".equals(toolName);
        }
        if (wantsScreenCapture(normalizedMessage)) {
            return "capture_screen".equals(toolName);
        }
        return intentRouter.shouldExposeTool(toolName, intentProfile);
    }

    public static boolean wantsScreenCapture(String normalizedMessage) {
        return MessageText.containsAny(
                normalizedMessage,
                "tira um print",
                "tirar um print",
                "tira print",
                "tirar print",
                "faz um print",
                "fazer um print",
                "print da minha tela",
                "print da tela",
                "screenshot",
                "captura minha tela",
                "capturar minha tela",
                "captura a tela",
                "capturar a tela");
    }

    private boolean wantsLocalProjectDiscovery(String normalizedMessage) {
        boolean asksToFind = MessageText.containsAny(
                normalizedMessage,
                "acha",
                "achar",
                "busca",
                "buscar",
                "encontra",
                "encontrar",
                "localiza",
                "localizar",
                "procura",
                "procurar");
        boolean refersToProjectOrPath = MessageText.containsAny(
                normalizedMessage, "caminho", "mac", "maquina", "pasta", "path", "projeto", "workspace");
        return asksToFind && refersToProjectOrPath;
    }
}
